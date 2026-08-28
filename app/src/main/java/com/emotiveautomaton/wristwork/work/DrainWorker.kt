package com.emotiveautomaton.wristwork.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.emotiveautomaton.wristwork.BuildConfig
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.net.NtfyClient
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Drains all three offline queues to their topics: labels -> tags, captured Fitbit notifications
 * -> flags, raw physiology batches -> health. WorkManager's network constraint IS the
 * replay-on-reconnect mechanism — no services, no alarms. Oldest-first; a failure stops that
 * queue's pass and retries with backoff.
 *
 * SIZE DISCIPLINE (found the hard way 2026-08-26): a heart-rate batch can exceed the server's
 * per-message size limit, and one oversized row at the head of the queue is a poison pill — every
 * pass dies on it, the backlog grows forever, and the failure is silent because a queue that
 * retries looks exactly like a queue that is working. Two guards now: payloads are split into
 * sample chunks below [MAX_BYTES] before posting, and each pass is capped so a large backlog
 * drains over several passes instead of running into WorkManager's ten-minute execution limit.
 */
class DrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private fun post(topic: String, body: String): Boolean = runCatching {
        NtfyClient.http.newCall(
            Request.Builder().url("${NtfyClient.baseUrl}/$topic")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use {
            // A silent drain failure is indistinguishable from a working queue, which is exactly
            // how a two-day backlog went unnoticed. Say why, out loud, every time.
            if (!it.isSuccessful) android.util.Log.e(
                "wristwork-drain", "post $topic failed http=${it.code} body=${it.body.string().take(120)}")
            it.isSuccessful
        }
    }.onFailure { android.util.Log.e("wristwork-drain", "post $topic threw", it) }
        .getOrDefault(false)

    override suspend fun doWork(): Result {
        val db = TagDb.get(applicationContext)
        var allOk = true

        for (e in db.tags().pending()) {
            val body = JsonObject(buildMap {
                put("event_id", JsonPrimitive(e.eventId))
                put("ts_event", JsonPrimitive(e.tsEvent))
                put("ts_entered", JsonPrimitive(e.tsEntered))
                put("primary", JsonPrimitive(e.primaryState))
                put("secondaries", JsonArray(e.secondaries.split(',').filter { it.isNotBlank() }
                    .map { JsonPrimitive(it) }))
                e.intensity?.let { put("intensity", JsonPrimitive(it)) }
                e.confidence?.let { put("confidence", JsonPrimitive(it)) }
                e.noticedBefore?.let { put("noticed_before", JsonPrimitive(it)) }
                e.note?.let { put("note", JsonPrimitive(it)) }
                put("source", JsonPrimitive(e.source))
                e.flagRef?.let { put("flag_ref", JsonPrimitive(it)) }
                e.promptId?.let { put("prompt_id", JsonPrimitive(it)) }
                e.promptTs?.let { put("prompt_ts", JsonPrimitive(it)) }
                e.revises?.let { put("revises", JsonPrimitive(it)) }
            }).toString()
            if (post(BuildConfig.TOPIC_TAGS, body)) db.tags().markUploaded(e.id) else { allOk = false; break }
        }

        for (f in db.flags().pending()) {
            val body = JsonObject(buildMap {
                put("ts", JsonPrimitive(f.ts))
                put("pkg", JsonPrimitive(f.pkg))
                f.title?.let { put("title", JsonPrimitive(it)) }
                f.text?.let { put("text", JsonPrimitive(it)) }
                put("kind", JsonPrimitive(f.kind))
                put("flag_id", JsonPrimitive(f.id))
            }).toString()
            if (post(BuildConfig.TOPIC_FLAGS, body)) db.flags().markUploaded(f.id) else { allOk = false; break }
        }

        var posts = 0
        for (b in db.raw().pending()) {
            if (posts >= MAX_POSTS_PER_PASS) { allOk = false; break }
            val parts = split(b.payload)
            var ok = true
            for (part in parts) {
                if (!post(BuildConfig.TOPIC_HEALTH, part)) { ok = false; break }
                posts++
            }
            if (ok) db.raw().markUploaded(b.id) else { allOk = false; break }
        }

        return if (allOk) Result.success() else Result.retry()
    }

    /**
     * One payload in, one or more payloads out, each under the size limit. Splits on the `samples`
     * array — the only field that grows — and stamps each piece so the archive can tell that a
     * batch arrived in pieces. A payload with no samples array is passed through untouched.
     */
    private fun split(payload: String): List<String> {
        if (payload.toByteArray().size <= MAX_BYTES) return listOf(payload)
        val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return listOf(payload)
        val samples = runCatching { obj["samples"]!!.jsonArray }.getOrNull()
            ?: return listOf(payload)
        val pieces = (payload.toByteArray().size / MAX_BYTES) + 1
        val per = (samples.size / pieces).coerceAtLeast(1)
        return samples.chunked(per).mapIndexed { i, chunk ->
            JsonObject(
                obj.filterKeys { it != "samples" } +
                    mapOf(
                        "samples" to JsonArray(chunk),
                        "part" to JsonPrimitive(i),
                        "parts" to JsonPrimitive((samples.size + per - 1) / per),
                    )
            ).toString()
        }
    }

    companion object {
        /** Comfortably under the server's limit, with room for the wrapper fields. */
        private const val MAX_BYTES = 12_000

        /** A backlog drains over several passes rather than one long one. */
        private const val MAX_POSTS_PER_PASS = 300

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<DrainWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()
            // REPLACE, not APPEND_OR_REPLACE. Appending behind a request that is in backoff means
            // one failure head-blocks the queue for as long as the backoff lasts — up to five
            // hours at WorkManager's cap — and every later enqueue politely queues up behind it.
            // That is how a two-day health backlog built up unnoticed (2026-08-26). The worker
            // drains whatever the database holds, so replacing a pending request loses nothing
            // and resets the backoff clock.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("drain-streams", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
