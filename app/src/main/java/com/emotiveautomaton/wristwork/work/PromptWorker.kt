package com.emotiveautomaton.wristwork.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.emotiveautomaton.wristwork.BuildConfig
import com.emotiveautomaton.wristwork.R
import com.emotiveautomaton.wristwork.net.NtfyClient
import com.emotiveautomaton.wristwork.ui.TagActivity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * Prompt delivery (detector design §1). The allocator on the rig posts prompts to the `prompts`
 * topic ahead of time; this poller fires the ones whose moment has come.
 *
 * Why a fifteen-minute poll and not a push: instant delivery on Wear needs a foreground service,
 * which the battery law bans. The owner accepted the ceiling (2026-08-26) because the prompt
 * carries the timestamp it is ASKING ABOUT — a late notification still tags the right moment.
 * Posting ahead of time also decouples the random stream (the permanent evaluation backbone, and
 * the scarcest resource in the project) from whether the rig happens to be awake at that minute.
 *
 * The copy is identical for `random` and `signal` prompts and asserts nothing about what was
 * detected. That blinding is what keeps the lift measurement honest, so the wording is built
 * HERE, from the timestamp alone, and never taken from the message.
 */
class PromptWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fired = prefs.getStringSet(KEY_FIRED, emptySet())!!.toMutableSet()
        val now = Instant.now().epochSecond

        val body = runCatching {
            val url = "${NtfyClient.baseUrl}/${BuildConfig.TOPIC_PROMPTS}/json?poll=1&since=12h"
            NtfyClient.http.newCall(Request.Builder().url(url).build()).execute()
                .use { if (it.isSuccessful) it.body.string() else null }
        }.getOrNull() ?: return Result.retry()

        var delivered = false
        for (line in body.lines().filter { it.isNotBlank() }) {
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["event"]?.jsonPrimitive?.content != "message") continue
            val msg = o["message"]?.jsonPrimitive?.content ?: continue
            val p = runCatching { Json.parseToJsonElement(msg).jsonObject }.getOrNull() ?: continue
            val id = p["prompt_id"]?.jsonPrimitive?.content ?: continue
            if (id in fired) continue
            val source = p["source"]?.jsonPrimitive?.content ?: continue
            if (source != "random" && source != "signal") continue
            val deliverAt = p["deliver_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            if (deliverAt > now) continue                       // its moment has not come yet
            // Stale prompts are dropped rather than asked late: an hour-old "how are you right
            // now" is a worse question than no question, and a skipped random prompt is honestly
            // a skipped random prompt rather than a mistimed answer in the evaluation set.
            if (now - deliverAt > STALE_AFTER_S) { fired += id; continue }
            val aboutS = p["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: deliverAt
            notify(ctx, id, source, aboutS)
            // The face carries it too, not just the notification (owner 2026-08-28).
            runCatching {
                com.emotiveautomaton.wristwork.data.CurrentState.setPromptPending(ctx, true)
                com.emotiveautomaton.wristwork.complication.StateComplicationService.requestUpdate(ctx)
            }
            fired += id
            delivered = true
        }
        // Bounded: the set only has to outlive the twelve-hour poll window.
        prefs.edit().putStringSet(KEY_FIRED, fired.toList().takeLast(200).toSet()).apply()
        return if (delivered || body.isNotEmpty()) Result.success() else Result.success()
    }

    private fun notify(ctx: Context, id: String, source: String, aboutS: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "state prompts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val about = Instant.ofEpochSecond(aboutS).atZone(ZoneId.systemDefault())
        val clock = about.format(DateTimeFormatter.ofPattern("h:mma"))
            .lowercase().removeSuffix("m")          // 2:41pm -> 2:41p, the design's copy
        val tap = PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, TagActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(TagActivity.EXTRA_PROMPT_ID, id)
                .putExtra(TagActivity.EXTRA_PROMPT_SOURCE, source)
                .putExtra(
                    TagActivity.EXTRA_PROMPT_TS,
                    about.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_state)
            .setContentTitle("State?")              // identical for random and signal, by design
            .setContentText("· $clock")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(id.hashCode(), n)
    }

    companion object {
        private const val PREFS = "prompts"
        private const val KEY_FIRED = "fired_ids"
        private const val CHANNEL = "prompts"
        private const val STALE_AFTER_S = 45L * 60L
        const val WORK_NAME = "prompt-poll"

        /** Idempotent; called from the channel refresh so it re-arms itself after a reboot or an
         *  app update without a boot receiver. KEEP, so an existing schedule is never restarted. */
        fun ensureScheduled(context: Context) {
            val req = PeriodicWorkRequestBuilder<PromptWorker>(Duration.ofMinutes(15))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
