package com.emotiveautomaton.wristwork.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.emotiveautomaton.wristwork.data.CurrentState
import com.emotiveautomaton.wristwork.ui.TagActivity

/**
 * Component A face: `SEEK 43m` — current state code + elapsed, deliberately cryptic (privacy lives
 * in the renderer). Face shows the state code alone for now. Tap opens the grid.
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

    // Design pass 2026-08-24 (HEALTH_DESIGN.md): humane display name + auto-ticking time since
    // entered ("to remind me to keep up with it"); the face-wide age ban is lifted for this
    // complication. When H2 ships, the same slot gains the model guess + confidence bar.
    private fun build(state: String, sinceEpochMs: Long?, tap: PendingIntent): ComplicationData {
        val name = com.emotiveautomaton.wristwork.data.StateNames.humane(state)
        val text = if (sinceEpochMs != null)
            androidx.wear.watchface.complications.data.TimeDifferenceComplicationText.Builder(
                androidx.wear.watchface.complications.data.TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                androidx.wear.watchface.complications.data.CountUpTimeReference(
                    java.time.Instant.ofEpochMilli(sinceEpochMs)),
            ).setText("$name·^1").build()
        else PlainComplicationText.Builder(name).build()
        return ShortTextComplicationData.Builder(
            text = text,
            contentDescription = PlainComplicationText.Builder("current state $name").build(),
        ).setTapAction(tap).build()
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
