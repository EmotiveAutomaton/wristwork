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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Drains all three offline queues to their topics: labels -> tags, captured Fitbit notifications
 * -> flags, raw physiology batches -> health. WorkManager's network constraint IS the
 * replay-on-reconnect mechanism — no services, no alarms. Oldest-first; a failure stops that
 * queue's pass and retries with backoff.
 */
class DrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private fun post(topic: String, body: String): Boolean = runCatching {
        NtfyClient.http.newCall(
            Request.Builder().url("${NtfyClient.baseUrl}/$topic")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { it.isSuccessful }
    }.getOrDefault(false)

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

        for (b in db.raw().pending()) {
            if (post(BuildConfig.TOPIC_HEALTH, b.payload)) db.raw().markUploaded(b.id) else { allOk = false; break }
        }

        return if (allOk) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<DrainWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("drain-streams", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
