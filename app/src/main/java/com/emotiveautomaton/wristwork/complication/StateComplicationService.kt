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

    // Elapsed-time text stripped from the face for now (owner, 2026-08-23); the tag timestamp
    // still lives in DataStore and the archive, and returns when this face gets its design pass.
    private fun build(state: String, sinceEpochMs: Long?, tap: PendingIntent): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(state).build(),
            contentDescription = PlainComplicationText.Builder("current state $state").build(),
        ).setTapAction(tap).build()

    companion object {
        /** Push a face refresh right after a tag (spec: immediate update). */
        fun requestUpdate(context: android.content.Context) {
            ComplicationDataSourceUpdateRequester.create(
                context, ComponentName(context, StateComplicationService::class.java),
            ).requestUpdateAll()
        }
    }
}
