package com.emotiveautomaton.wristwork.complication

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
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
        // Piggybacked on the 15-minute channel refresh because the state provider updates only on
        // demand: whichever fires first files the device capability inventory, once per version.
        com.emotiveautomaton.wristwork.health.SensorInventory.postOnce(applicationContext)
        // Same free ride for the prompt poller's schedule (KEEP, so this never restarts it).
        com.emotiveautomaton.wristwork.work.PromptWorker.ensureScheduled(applicationContext)
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

    /** Optional second line for SHORT_TEXT slots (faces place/truncate it as they see fit). */
    open fun titleText(message: String): String? = null

    /** Optional monochrome icon (face-tinted); may vary with the payload (thresholds). */
    open fun iconRes(message: String): Int? = null

    /** Name chars fitting beside the dot and age in a ~7-char field: a short age ("7M","2H","3D")
     *  buys a fourth letter; two-digit ages leave three. Ages tick between renders, so the budget
     *  follows what the age looks like for most of the ~15-min refresh window. */
    protected fun nameBudget(epochS: Long): Int {
        val mins = (java.time.Instant.now().epochSecond - epochS) / 60
        // MEASURED, not guessed (2026-08-26): this slot renders "GHO·5" and then ellipsises the
        // "H" away. It is a pixel width — roughly five and a half capitals with the icon present —
        // so the old character budgets were always over, and the parentheses had been hiding it by
        // being narrow. Two letters is what fits without ever showing "...", and two letters still
        // separates every project on this machine (GH, SO, WR, FE, HM, WE, LO, BU, SI).
        return when {
            mins < 60 -> 2            // "GH·17M"
            mins < 10 * 60 -> 2       // "GH·5H"
            mins < 24 * 60 -> 2       // "GH·14H"
            mins < 10 * 24 * 60 -> 2  // "GH·3D"
            else -> 2
        }
    }

    /** When true, SHORT_TEXT text becomes the auto-ticking age and format() moves to the title;
     *  LONG_TEXT wraps the age into "{longFormat} - {age}". Per-complication choice (owner),
     *  and per-MESSAGE: the printer wants a live percent while printing and a ticking
     *  time-since-finished when it is not. */
    open fun ageAsText(message: String): Boolean = false

    /** With ageAsText, true drops the name entirely and shows the bare ticking age. Used where
     *  the icon already says what the age is about (printer icon + "3H" = finished 3 h ago). */
    open fun ageOnly(message: String): Boolean = false

    private fun monoImage(message: String): androidx.wear.watchface.complications.data.MonochromaticImage? =
        iconRes(message)?.let {
            androidx.wear.watchface.complications.data.MonochromaticImage.Builder(
                android.graphics.drawable.Icon.createWithResource(this, it)
            ).build()
        }

    /** Longer rendering for LONG_TEXT-capable slots; defaults to the short form. */
    open fun longFormat(message: String): String? = format(message)

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
        // Parentheses mark staleness only where nothing else does. A face that already draws a
        // ticking age states its own staleness far better, and the brackets were eating two of
        // the seven characters the name gets (owner 2026-08-25: "I want the extra letter").
        val marksAge = ageAsText(payload)
        val shown = if (age > Duration.ofHours(2) && !marksAge) "(${short.lowercase()})" else short
        val desc = PlainComplicationText.Builder("$topic: $shown").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                // ageAsText: one combined text field "name·age" — the title field was what the
                // face ellipsized ("SOUN..."); a single text renders as many chars as the slot fits.
                // ageAsText: title carries "name·" (clipped by us, never face-ellipsized) and
                // the text field carries the bare ticking age — two fields = two budgets.
                val textPart = if (ageAsText(payload)) {
                    val tb = androidx.wear.watchface.complications.data.TimeDifferenceComplicationText.Builder(
                        androidx.wear.watchface.complications.data.TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                        androidx.wear.watchface.complications.data.CountUpTimeReference(
                            Instant.ofEpochSecond(epochS)),
                    )
                    if (!ageOnly(payload)) tb.setText("${shown.take(nameBudget(epochS))}·^1")
                    tb.build()
                } else PlainComplicationText.Builder(shown).build()
                val b = ShortTextComplicationData.Builder(text = textPart, contentDescription = desc)
                    .setTapAction(tapIntent())
                if (!ageAsText(payload)) titleText(payload)?.let { b.setTitle(PlainComplicationText.Builder(it).build()) }
                monoImage(payload)?.let { b.setMonochromaticImage(it) }
                b.build()
            }
            ComplicationType.LONG_TEXT -> {
                val long = longFormat(payload) ?: return NoDataComplicationData()
                val shownLong = if (age > Duration.ofHours(2) && !marksAge) "(${long.lowercase()})" else long
                val longText = if (ageAsText(payload))
                    androidx.wear.watchface.complications.data.TimeDifferenceComplicationText.Builder(
                        androidx.wear.watchface.complications.data.TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                        androidx.wear.watchface.complications.data.CountUpTimeReference(
                            Instant.ofEpochSecond(epochS)),
                    ).setText("$shownLong \u00b7 ^1").build()
                else PlainComplicationText.Builder(shownLong).build()
                val lb = LongTextComplicationData.Builder(
                    text = longText,
                    contentDescription = desc,
                ).setTapAction(tapIntent())
                monoImage(payload)?.let { lb.setMonochromaticImage(it) }
                lb.build()
            }
            ComplicationType.RANGED_VALUE -> {
                val v = gaugeValue(payload) ?: return null
                val rb = RangedValueComplicationData.Builder(
                    value = v.coerceIn(0f, 99f), min = 0f, max = 99f,
                    contentDescription = desc,
                ).setText(PlainComplicationText.Builder(shown).build())
                    .setTapAction(tapIntent())
                monoImage(payload)?.let { rb.setMonochromaticImage(it) }
                rb.build()
            }
            else -> null
        }
    }
}

/** `agents` — Claude Code and friends. "done: wristwork" -> "done", "needs input: x" -> "INPUT". */
class AgentsComplicationService : ChannelComplicationService() {
    override val topic get() = com.emotiveautomaton.wristwork.BuildConfig.TOPIC_AGENTS
    override fun ageAsText(message: String): Boolean = true
    override fun tapActivity(): Class<*> = com.emotiveautomaton.wristwork.ui.AgentsDetailActivity::class.java
    override fun iconRes(message: String): Int = com.emotiveautomaton.wristwork.R.drawable.ic_agents
    private fun project(message: String): String? =
        Regex("^done:\\s*(.+)$", RegexOption.IGNORE_CASE)
            .find(message.trim())?.groupValues?.get(1)?.trim()
    override fun format(message: String): String {
        val m = message.trim()
        return when {
            m.startsWith("needs input", ignoreCase = true) -> "INPUT"
            // 4 chars, clipped by us: the face ellipsizes anything longer and the age (the
            // part that ticks) is what falls off the end. No "..." ever.
            else -> (project(m) ?: m).take(8)  // clipped at render by nameBudget()
        }
    }
    override fun longFormat(message: String): String? {
        val m = message.trim()
        return if (m.startsWith("needs input", ignoreCase = true)) "INPUT" else (project(m) ?: m).take(40)
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
    /** Quintile circle-fill glyph (baseline-friendly, unlike the block glyphs); the top
     *  quintile escalates to "!" and its letter goes uppercase. Letters are lowercase otherwise
     *  (faces that force uppercase will flatten this — the data is still correct). */
    private fun glyph(v: Int): String = when {
        v >= 80 -> "!"
        v >= 60 -> "\u25d5"   // three-quarter circle
        v >= 40 -> "\u25d1"   // half circle
        v >= 20 -> "\u25d4"   // quarter circle
        else -> "\u25cb"      // empty circle
    }
    private fun part(letter: String, v: Int): String {
        val g = glyph(v)
        return (if (g == "!") letter.uppercase() else letter.lowercase()) + g
    }
    private fun num(message: String, k: String): Int? = runCatching {
        Json.parseToJsonElement(message).jsonObject[k]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()
    }.getOrNull()
    /** Per-component critical temps (researched 2026-08-24): NVIDIA core throttles 83-90 so
     *  gpu >= 90 alarms; Ryzen 7000 parks at its 95 TjMax BY DESIGN so cpu alarms only above it. */
    private fun tempCritical(message: String): Boolean =
        (num(message, "tg")?.let { it >= 90 } == true) || (num(message, "tc")?.let { it >= 97 } == true)
    private fun triple(message: String, sep: String): String? {
        if (tempCritical(message)) return "!!!!!!"
        return listOfNotNull(
            pct(message, "cpu")?.let { part("c", it) },
            // G is driven by the busier of gpu/vram (owner 2026-08-24)
            listOfNotNull(pct(message, "gpu"), pct(message, "vram")).maxOrNull()?.let { part("g", it) },
            pct(message, "ram")?.let { part("r", it) },
        ).joinToString(sep).ifEmpty { null }
    }
    override fun format(message: String): String = triple(message, " ") ?: message.take(6)
    override fun longFormat(message: String): String? = triple(message, " ")
    override fun iconRes(message: String): Int {
        fun num(k: String) = runCatching {
            Json.parseToJsonElement(message).jsonObject[k]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()
        }.getOrNull()
        val tempCritical = (num("tg")?.let { it >= 90 } == true) || (num("tc")?.let { it >= 97 } == true)
        val loadHigh = listOfNotNull(pct(message, "cpu"), pct(message, "gpu"),
            pct(message, "ram"), pct(message, "vram")).any { it > 90 }
        return when {
            tempCritical -> com.emotiveautomaton.wristwork.R.drawable.ic_chip_crit
            loadHigh -> com.emotiveautomaton.wristwork.R.drawable.ic_chip_alert
            else -> com.emotiveautomaton.wristwork.R.drawable.ic_chip
        }
    }
    override fun gaugeValue(message: String): Float? =
        listOfNotNull(pct(message, "cpu"), pct(message, "gpu"), pct(message, "ram"))
            .maxOrNull()?.toFloat()
}

/**
 * `printer` — ALWAYS VISIBLE (owner 2026-08-24). While a print runs the face shows the live
 * percent. Otherwise it shows the printer icon plus a bare ticking age, which reads as
 * "last print finished N ago" — the record posts at the moment of completion, so the message's
 * own timestamp IS the completion time. Legacy/startup `idle` has no meaningful completion
 * moment and renders as the word, never as an age (an age there would be freshness theatre).
 *
 * Payload contract (poller.sh, also a Fetch-facing contract):
 *   "{n}%"                                    while printing, every 5 % step
 *   "paused" / "ATTN"                         state transitions
 *   "done · {name} · {dur} · 100%"          print completed
 *   "stopped · {name} · {dur} · {n}%"       print cancelled or errored out
 */
class PrinterComplicationService : ChannelComplicationService() {
    override val topic get() = com.emotiveautomaton.wristwork.BuildConfig.TOPIC_PRINTER
    override fun tapActivity(): Class<*> = com.emotiveautomaton.wristwork.ui.PrinterDetailActivity::class.java
    override fun iconRes(message: String): Int = com.emotiveautomaton.wristwork.R.drawable.ic_printer
    private fun isRecord(message: String): Boolean {
        val m = message.trim()
        return m.startsWith("done", ignoreCase = true) || m.startsWith("stopped", ignoreCase = true)
    }
    override fun ageAsText(message: String): Boolean = isRecord(message)
    override fun ageOnly(message: String): Boolean = isRecord(message)
    override fun format(message: String): String {
        val m = message.trim()
        return when {
            isRecord(m) -> "done"          // not drawn (ageOnly); carries the content description
            m.isEmpty() -> "idle"
            else -> m.take(7)
        }
    }
    override fun longFormat(message: String): String =
        if (isRecord(message)) "last print" else format(message)
}
