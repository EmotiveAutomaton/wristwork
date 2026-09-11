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
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.net.NtfyClient
import com.emotiveautomaton.wristwork.ui.TagActivity
import java.time.OffsetDateTime
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
            // 36 h, not 12: prompts are allocated up to a day in advance, so a twelve-hour window
            // made every afternoon prompt invisible by the time its moment arrived — the
            // 08-27 prompt was never delivered and looked exactly like an ignored one.
            val url = "${NtfyClient.baseUrl}/${BuildConfig.TOPIC_PROMPTS}/json?poll=1&since=36h"
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
            if (now - deliverAt > STALE_AFTER_S && !wasDeferred(prefs, id)) {
                fired += id
                // If THIS is the question the face is still advertising, retire it: a marker that
                // outlives the question it stands for is worse than no marker.
                runCatching {
                    val held = com.emotiveautomaton.wristwork.data.CurrentState.read(ctx)
                    if (held.promptId == id) {
                        com.emotiveautomaton.wristwork.data.CurrentState.clearPrompt(ctx)
                        com.emotiveautomaton.wristwork.complication.StateComplicationService
                            .requestUpdate(ctx)
                    }
                }
                continue
            }
            var aboutS = p["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: deliverAt

            // ONE HOUR AROUND ANY EXISTING EVENT (owner, 2026-09-02) -- but a random question is
            // MOVED, never cancelled, and this is the owner's correction to a worse plan of mine.
            // Exempting the random stream from the hour rule would have made it the only kind of
            // question that can appear beside another event, which makes it IDENTIFIABLE as the
            // control. A control the wearer can recognise has stopped being a control, and no
            // amount of care afterwards recovers that. Cancelling it instead would bias the
            // evaluation set by dropping exactly the moments that follow notable ones.
            //
            // So it waits. Each pass re-checks; when the hour is finally clear it fires, asking
            // about a FRESH moment rather than the stale one it was allocated for -- which is the
            // same shape a detector question has, and keeps the two indistinguishable. Deferral
            // is capped, because a question deferred forever is a cancelled question in disguise.
            if (crowded(ctx, now)) {
                if (source != "random") {
                    // A detector question names a specific scored moment. Moving it would make it
                    // ask about a moment nothing was ever detected at, which is a lie dressed as
                    // data -- so it lapses instead, and the detector finds another moment later.
                    // The wearer never sees a question that was not sent, so nothing about this
                    // is visible from the outside and blinding is untouched.
                    fired += id
                    continue
                }
                val since = deferredSince(prefs, id, now)
                if (now - since <= DEFER_GIVE_UP_S) {
                    android.util.Log.i("wristwork-prompt", "deferring random prompt $id: an event is within the hour")
                    continue                       // NOT marked fired -- it comes back next pass
                }
                android.util.Log.w("wristwork-prompt", "gave up on $id after deferring it for hours")
                fired += id
                clearDeferral(prefs, id)
                continue
            }
            if (wasDeferred(prefs, id)) {
                // Relocated, lag and all. A question held back must not then ask about an hour
                // ago, and it must carry the same gap between naming and arriving that a detector
                // question carries, or the delay itself becomes the tell.
                aboutS = now - LAGS_S.random()
                clearDeferral(prefs, id)
            }
            notify(ctx, id, source, aboutS)
            // The question is RECORDED, not merely announced (owner 2026-09-01). The notification
            // is one door to it; the face is another, and the grid opened from either must know
            // the same thing — which moment is being asked about, and which question it answers.
            runCatching {
                com.emotiveautomaton.wristwork.data.CurrentState.setPrompt(
                    ctx, id, source,
                    Instant.ofEpochSecond(aboutS).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                com.emotiveautomaton.wristwork.complication.StateComplicationService.requestUpdate(ctx)
            }
            fired += id
            delivered = true
        }
        // Bounded: the set only has to outlive the poll window.
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
        // Clearing the notification means "skip this question." Without this, the notification
        // disappeared while CurrentState kept promptPending forever; six hours later its marker
        // also fell outside the timeline, leaving a NEW face that opened onto no triangle.
        val dismiss = PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, PromptDismissReceiver::class.java)
                .putExtra(PromptDismissReceiver.EXTRA_PROMPT_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_state)
            .setContentTitle("State?")              // identical for random and signal, by design
            .setContentText("· $clock")
            .setContentIntent(tap)
            .setDeleteIntent(dismiss)
            .setAutoCancel(true)
            .build()
        nm.notify(id.hashCode(), n)
    }

    /** Is there already an event within the hour, in either direction? Mirrors CueWorker. */
    private suspend fun crowded(ctx: Context, now: Long): Boolean {
        val db = TagDb.get(ctx)
        fun near(iso: String?): Boolean = iso != null && runCatching {
            kotlin.math.abs(OffsetDateTime.parse(iso).toEpochSecond() - now) < BRACKET_S
        }.getOrDefault(false)
        if (db.tags().latestEvents(12).any { near(it.tsEntered) || near(it.tsEvent) }) return true
        return db.flags().latestBodyResponses(6).any { near(it.ts) }
    }

    private fun deferredSince(prefs: android.content.SharedPreferences, id: String, now: Long): Long {
        val existing = prefs.getLong(KEY_DEFER + id, 0L)
        if (existing > 0L) return existing
        prefs.edit().putLong(KEY_DEFER + id, now).apply()
        return now
    }

    private fun wasDeferred(prefs: android.content.SharedPreferences, id: String) =
        prefs.getLong(KEY_DEFER + id, 0L) > 0L

    private fun clearDeferral(prefs: android.content.SharedPreferences, id: String) {
        prefs.edit().remove(KEY_DEFER + id).apply()
    }

    companion object {
        private const val PREFS = "prompts"
        private const val KEY_FIRED = "fired_ids"
        private const val KEY_DEFER = "deferred_since_"

        /** Matches the detector's own bracket (tools/rig/detect.py, EVENT_BRACKET_MIN). */
        private const val BRACKET_S = 60L * 60L

        /** A question held back this long has missed its day; recorded as never asked. */
        private const val DEFER_GIVE_UP_S = 6L * 60L * 60L

        /**
         * How far behind its delivery a question names its moment. A detector question is one
         * five-minute epoch plus a poll cycle behind — measured across every question ever sent:
         * 5, 10, 15 or 20 minutes. A relocated random question draws from the same set, because
         * an identical question that arrives with a different lag is not identical.
         */
        private val LAGS_S = listOf(5L, 10L, 15L, 20L).map { it * 60L }
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
