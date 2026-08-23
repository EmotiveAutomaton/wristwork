package com.emotiveautomaton.wristwork.complication

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.emotiveautomaton.wristwork.net.NtfyClient
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * Component B: one generic provider parameterized by topic + formatter (D5: Android requires a
 * concrete service class per data source, so instantiations are three-line subclasses).
 *
 * Polls `{server}/{topic}/json?poll=1&since={last}` on the platform's scheduled refresh
 * (UPDATE_PERIOD_SECONDS in the manifest; the ~15-minute floor is accepted). No alarms, no
 * services, no wake locks — the battery budget is an acceptance criterion.
 *
 * Rendering: `{payload}` as title, auto-ticking age as text. Stale (>2h) payloads render
 * lowercased in parens — visibly aged, never mistakable for fresh. A formatter returning null
 * renders NO_DATA and the complication disappears (the printer between prints).
 */
// File-scope, not a class member: a member delegate would mint one DataStore per service
// instance over the same file, and the second access crashes the process (found on-device
// 2026-08-23 — every channel refresh after the first was dying with IllegalStateException).
private val Context.channelStore by preferencesDataStore(name = "channels")

abstract class ChannelComplicationService : SuspendingComplicationDataSourceService() {

    /** Topic name, resolved from BuildConfig by the subclass. */
    abstract val topic: String

    /** Message payload -> short face text (~7 chars), or null for NO_DATA. */
    abstract fun format(message: String): String?
    private fun keyPayload() = stringPreferencesKey("$topic.payload")
    private fun keyTime() = longPreferencesKey("$topic.epoch_s")

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        render("42", Instant.now().epochSecond, type)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val (payload, epochS) = withContext(Dispatchers.IO) { poll() }
        if (payload == null || epochS == null) return NoDataComplicationData()
        return render(payload, epochS, request.complicationType)
    }

    /** Returns the newest known (payload, epoch seconds) for the topic, network permitting. */
    private suspend fun poll(): Pair<String?, Long?> {
        val p = applicationContext.channelStore.data.firstOrNull()
        var lastPayload: String? = p?.get(keyPayload())
        var lastTime: Long? = p?.get(keyTime())
        val since = lastTime?.toString() ?: "all"
        runCatching {
            val url = "${NtfyClient.baseUrl}/$topic/json?poll=1&since=$since"
            NtfyClient.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching
                val lines = resp.body.string().trim().lines().filter { it.isNotBlank() }
                val newest = lines.lastOrNull {
                    runCatching {
                        Json.parseToJsonElement(it).jsonObject["event"]?.jsonPrimitive?.content == "message"
                    }.getOrDefault(false)
                } ?: return@runCatching
                val obj = Json.parseToJsonElement(newest).jsonObject
                lastPayload = obj["message"]?.jsonPrimitive?.content
                lastTime = obj["time"]?.jsonPrimitive?.content?.toLongOrNull()
                applicationContext.channelStore.edit {
                    lastPayload?.let { p -> it[keyPayload()] = p }
                    lastTime?.let { t -> it[keyTime()] = t }
                }
            }
        }
        // Network failure: fall through with the cached value — its age keeps ticking on the face,
        // which is exactly the honest signal (stale never reads fresh).
        return lastPayload to lastTime
    }

    /** Subclasses may supply a tap-frame activity; null = no tap action. */
    open fun tapActivity(): Class<*>? = null

    /** Subclasses may supply a 0-99 gauge value for RANGED_VALUE slots; null = unsupported. */
    open fun gaugeValue(message: String): Float? = null

    private fun tapIntent(): android.app.PendingIntent? = tapActivity()?.let {
        android.app.PendingIntent.getActivity(
            this, 0,
            android.content.Intent(this, it).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Age counters are stripped from the face for now (owner, 2026-08-23); staleness still marks
    // itself as "(lowercase)". Age displays return per-complication as each gets its design pass.
    private fun render(payload: String, epochS: Long, type: ComplicationType): ComplicationData? {
        val short = format(payload) ?: return NoDataComplicationData()
        val age = Duration.between(Instant.ofEpochSecond(epochS), Instant.now())
        val shown = if (age > Duration.ofHours(2)) "(${short.lowercase()})" else short
        val desc = PlainComplicationText.Builder("$topic: $shown").build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(shown).build(),
                    contentDescription = desc,
                ).setTapAction(tapIntent()).build()
            ComplicationType.RANGED_VALUE -> {
                val v = gaugeValue(payload) ?: return null
                RangedValueComplicationData.Builder(
                    value = v.coerceIn(0f, 99f), min = 0f, max = 99f,
                    contentDescription = desc,
                ).setText(PlainComplicationText.Builder(shown).build())
                    .setTapAction(tapIntent()).build()
            }
            else -> null
        }
    }
}

/** `agents` — Claude Code and friends. "done: wristwork" -> "done", "needs input: x" -> "INPUT". */
class AgentsComplicationService : ChannelComplicationService() {
    override val topic get() = com.emotiveautomaton.wristwork.BuildConfig.TOPIC_AGENTS
    override fun format(message: String): String {
        val m = message.trim()
        return when {
            m.startsWith("needs input", ignoreCase = true) -> "INPUT"
            m.startsWith("done", ignoreCase = true) -> "done"
            else -> m.take(7)
        }
    }
}

/** `rig` — workstation stats JSON -> "c72 g41" style; falls back to raw prefix. */
class RigComplicationService : ChannelComplicationService() {
    override val topic get() = com.emotiveautomaton.wristwork.BuildConfig.TOPIC_RIG
    override fun tapActivity(): Class<*> = com.emotiveautomaton.wristwork.ui.RigDetailActivity::class.java
    private fun pct(message: String, key: String): Int? = runCatching {
        Json.parseToJsonElement(message).jsonObject[key]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?.toInt()?.coerceIn(0, 99)
    }.getOrNull()
    override fun format(message: String): String =
        listOfNotNull(
            pct(message, "cpu")?.let { "c$it" },
            pct(message, "gpu")?.let { "g$it" },
        ).joinToString("").ifEmpty { message.take(6) }
    override fun gaugeValue(message: String): Float? =
        listOfNotNull(pct(message, "cpu"), pct(message, "gpu"), pct(message, "ram"))
            .maxOrNull()?.toFloat()
}

/** `printer` — progress/state; `idle` (and staleness) disappears the complication entirely. */
class PrinterComplicationService : ChannelComplicationService() {
    override val topic get() = com.emotiveautomaton.wristwork.BuildConfig.TOPIC_PRINTER
    override fun format(message: String): String? {
        val m = message.trim()
        return if (m.equals("idle", ignoreCase = true)) null else m.take(7)
    }
}
