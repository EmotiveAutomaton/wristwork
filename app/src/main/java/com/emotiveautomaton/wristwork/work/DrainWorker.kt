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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Drains the offline tag queue to the `tags` topic. Enqueued after every tap; WorkManager's
 * network constraint IS the replay-on-reconnect mechanism — no services, no alarms (D4).
 * Rows upload oldest-first; a failure stops the pass and retries with backoff.
 */
class DrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = TagDb.get(applicationContext).tags()
        val url = "${NtfyClient.baseUrl}/${BuildConfig.TOPIC_TAGS}"
        for (e in dao.pending()) {
            val body = JsonObject(buildMap {
                put("ts", JsonPrimitive(e.ts))
                put("state", JsonPrimitive(e.state))
                put("noticed", JsonPrimitive(e.noticed))
                e.note?.let { put("note", JsonPrimitive(it)) }
                put("source", JsonPrimitive(e.source))
            }).toString()
            val ok = runCatching {
                NtfyClient.http.newCall(
                    Request.Builder().url(url)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (!ok) return Result.retry()
            dao.markUploaded(e.id)
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<DrainWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("drain-tags", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
