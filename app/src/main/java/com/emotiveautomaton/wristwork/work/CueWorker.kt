package com.emotiveautomaton.wristwork.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.emotiveautomaton.wristwork.R
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.ui.TagActivity
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The delayed cue (HEALTH_DESIGN.md): fires CUE_DELAY_MIN minutes after a caught body-response
 * flag — Fitbit's own push covered the moment itself; ours asks for the label once the moment
 * has settled. Tapping opens the grid pre-linked to the flag. WorkManager's inexact delay
 * (± minutes) is accepted: an exact-time cue would be an alarm, which stays banned.
 */
class CueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val flagId = inputData.getLong(KEY_FLAG_ID, -1L)
        // ONE HOUR AROUND ANY EXISTING EVENT IS OFF LIMITS (owner, 2026-09-02). A body response
        // caught minutes after the wearer already labelled something is almost always the label
        // itself -- looking at a watch and thinking about feelings moves a heart rate, and Fitbit
        // notices. Asking about it produces a second event describing the same moment, which
        // crowds the timeline and teaches a model that being asked is a feeling. Dropped in
        // silence, on purpose: there is nothing here for the wearer to dismiss or decide.
        if (crowded()) {
            android.util.Log.i("wristwork-cue", "cue suppressed: an event is within the hour")
            return Result.success()
        }
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "state cues", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val tap = PendingIntent.getActivity(
            applicationContext, flagId.toInt(),
            Intent(applicationContext, TagActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(TagActivity.EXTRA_FLAG_REF, flagId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_state)
            .setContentTitle("tag it?")
            .setContentText("a body response was flagged earlier")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(flagId.toInt(), n)
        return Result.success()
    }

    /** Is there already an event within the hour, in either direction? */
    private suspend fun crowded(): Boolean {
        val db = TagDb.get(applicationContext)
        val now = Instant.now().epochSecond
        fun near(iso: String?): Boolean = iso != null && runCatching {
            kotlin.math.abs(OffsetDateTime.parse(iso).toEpochSecond() - now) < BRACKET_S
        }.getOrDefault(false)
        // Labels the wearer entered, by both clocks: when it was written and what it describes.
        if (db.tags().latestEvents(12).any { near(it.tsEntered) || near(it.tsEvent) }) return true
        // ...and body responses already flagged, so a burst of them yields one cue, not four.
        return db.flags().latestBodyResponses(6).any { near(it.ts) }
    }

    companion object {
        const val KEY_FLAG_ID = "flag_id"
        const val CHANNEL = "cues"

        /** Matches the detector's own bracket (tools/rig/detect.py, EVENT_BRACKET_MIN). */
        private const val BRACKET_S = 60L * 60L
    }
}
