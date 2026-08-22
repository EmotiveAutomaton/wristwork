package com.emotiveautomaton.wristwork.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.emotiveautomaton.wristwork.net.NtfyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 3 network probe. Renders `UP 123ms` or `DOWN`. If this shows a latency number on the face,
 * watch -> tailnet -> ntfy works end to end; if it shows DOWN over the tailnet, the config switches
 * to ntfy.sh (a config edit, not a redesign).
 */
class HealthComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText("UP 42ms", "health up 42 ms") else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val h = withContext(Dispatchers.IO) { NtfyClient.health() }
        val text = if (h.up) "UP ${h.latencyMs}ms" else "DOWN"
        return shortText(text, if (h.up) "server up, ${h.latencyMs} ms" else "server down")
    }

    private fun shortText(text: String, description: String) =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(description).build(),
        ).build()
}
