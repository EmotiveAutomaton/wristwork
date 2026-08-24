package com.emotiveautomaton.wristwork.flags

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.emotiveautomaton.wristwork.data.FlagEvent
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.work.CueWorker
import com.emotiveautomaton.wristwork.work.DrainWorker
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Family-1 capture (HEALTH_DESIGN.md): logs every Fitbit-package notification into the `flags`
 * stream — that wholesale log is both the body-response capture AND the diagnostic for the
 * known push intermittency (compare against the in-app timeline) AND the canary's raw material
 * (a stretch of silence means the string-matching broke, and silence must never read as calm).
 *
 * A body-response match additionally schedules the delayed cue notification that reopens the
 * grid pre-linked to this flag. Platform-bound service: the OS wakes it per notification;
 * no polling, no wake locks of our own.
 */
class FlagListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!pkg.contains("fitbit", ignoreCase = true)) return
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        val haystack = "${title.orEmpty()} ${text.orEmpty()}"
        val isBodyResponse = Regex("body\\s*respon", RegexOption.IGNORE_CASE).containsMatchIn(haystack)
        val kind = if (isBodyResponse) "body-response" else "fitbit-notif"
        val ctx = applicationContext
        scope.launch {
            val id = TagDb.get(ctx).flags().insert(
                FlagEvent(
                    ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    pkg = pkg, title = title, text = text, kind = kind,
                )
            )
            DrainWorker.enqueue(ctx)
            if (isBodyResponse) {
                val req = OneTimeWorkRequestBuilder<CueWorker>()
                    .setInitialDelay(Duration.ofMinutes(
                        com.emotiveautomaton.wristwork.BuildConfig.CUE_DELAY_MIN.toLong()))
                    .setInputData(androidx.work.workDataOf(CueWorker.KEY_FLAG_ID to id))
                    .build()
                WorkManager.getInstance(ctx).enqueue(req)
            }
        }
    }
}
