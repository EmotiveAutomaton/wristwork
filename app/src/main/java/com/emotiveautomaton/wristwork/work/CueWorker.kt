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
import com.emotiveautomaton.wristwork.ui.TagActivity

/**
 * The delayed cue (HEALTH_DESIGN.md): fires CUE_DELAY_MIN minutes after a caught body-response
 * flag — Fitbit's own push covered the moment itself; ours asks for the label once the moment
 * has settled. Tapping opens the grid pre-linked to the flag. WorkManager's inexact delay
 * (± minutes) is accepted: an exact-time cue would be an alarm, which stays banned.
 */
class CueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val flagId = inputData.getLong(KEY_FLAG_ID, -1L)
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

    companion object {
        const val KEY_FLAG_ID = "flag_id"
        const val CHANNEL = "cues"
    }
}
