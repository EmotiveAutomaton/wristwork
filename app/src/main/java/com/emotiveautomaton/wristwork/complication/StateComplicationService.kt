package com.emotiveautomaton.wristwork.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.emotiveautomaton.wristwork.data.CurrentState
import com.emotiveautomaton.wristwork.ui.TagActivity
import java.time.Instant

/**
 * Component A face: `SEEK 43m` — current state code + elapsed, deliberately cryptic (privacy lives
 * in the renderer). Elapsed uses TimeDifferenceComplicationText so the face ticks by itself with
 * zero updates from us. Tap opens the grid.
 */
class StateComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build("SEEK", null, tapIntent()) else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val snap = CurrentState.read(this)
        return build(snap.state ?: "—", snap.sinceEpochMs, tapIntent())
    }

    private fun tapIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, TagActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun build(state: String, sinceEpochMs: Long?, tap: PendingIntent): ComplicationData {
        val title = PlainComplicationText.Builder(state).build()
        val text = if (sinceEpochMs != null)
            TimeDifferenceComplicationText.Builder(
                TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                CountUpTimeReference(Instant.ofEpochMilli(sinceEpochMs)),
            ).build()
        else PlainComplicationText.Builder("tap").build()
        return ShortTextComplicationData.Builder(
            text = text,
            contentDescription = PlainComplicationText.Builder("current state $state").build(),
        ).setTitle(title).setTapAction(tap).build()
    }

    companion object {
        /** Push a face refresh right after a tag (spec: immediate update). */
        fun requestUpdate(context: android.content.Context) {
            ComplicationDataSourceUpdateRequester.create(
                context, ComponentName(context, StateComplicationService::class.java),
            ).requestUpdateAll()
        }
    }
}
