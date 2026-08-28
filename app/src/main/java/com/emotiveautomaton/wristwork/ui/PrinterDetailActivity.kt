package com.emotiveautomaton.wristwork.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.emotiveautomaton.wristwork.BuildConfig
import com.emotiveautomaton.wristwork.net.NtfyClient
import com.emotiveautomaton.wristwork.net.PrusaClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val HEADER_COLOR = Color(0xFF9EB8D8)
private val ACCENT = Color(0xFFFFB36B)
private val BAR_TRACK = Color(0xFF2A2F3A)

private data class PrinterView(
    val state: String, val stateText: String?, val progress: Int?,
    val remainingS: Long?, val printingS: Long?,
    val nozzle: Double?, val nozzleTarget: Double?, val bed: Double?, val bedTarget: Double?,
    val material: String?, val displayName: String?, val thumbPath: String?,
) { val active: Boolean get() = progress != null }

/** The record of the last finished print, read off the bus (the printer itself forgets a job the
 *  moment it is dismissed on its screen, so the topic is the only durable record). */
private data class PrintRecord(
    val finished: Boolean, val name: String?, val duration: String?, val percent: Int?,
    val epochS: Long, val thumbUrl: String?,
)

/**
 * The printer tap-frame. While a print runs (v2): thumbnail, print name, `state · NN%` headline
 * (the state text is the detailed one — "absorbing heat" — when PrusaLink offers it), a timeline
 * bar (start time on the left, total expected duration on the right, remaining following the
 * fill), one temperature line, material. Refreshes every 5 s while open.
 *
 * Between prints (owner 2026-08-24): the record of the last print — its name, a full bar at
 * 100 %, the clock time it finished and how long it took. That record comes off the bus, not the
 * printer: PrusaLink drops the job as soon as the completion screen is dismissed, and the watch
 * must still answer "when did it finish" hours later, printer asleep or not.
 */
class PrinterDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WristTheme {
                var v by remember { mutableStateOf<PrinterView?>(null) }
                var thumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                var status by remember { mutableStateOf("loading…") }
                var record by remember { mutableStateOf<PrintRecord?>(null) }
                LaunchedEffect(Unit) {
                    var lastThumbPath: String? = null
                    var lastRecordThumb: String? = null
                    var lanTriedFor: String? = null
                    while (true) {
                        withContext(Dispatchers.IO) {
                            val nv = fetch()
                            v = nv
                            if (nv != null && nv.active) {
                                status = ""
                                if (nv.thumbPath != null && nv.thumbPath != lastThumbPath) {
                                    PrusaClient.get(nv.thumbPath)?.let { bytes ->
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                                            thumb = it; lastThumbPath = nv.thumbPath
                                        }
                                    }
                                }
                            } else {
                                // Idle, or the printer is off/unreachable: the bus still remembers.
                                lastThumbPath = null
                                val r = lastRecord()
                                record = r
                                // Say what we have as soon as we have it: the picture below can
                                // take a few more seconds and "loading…" should not outlive the
                                // text it belongs to.
                                status = if (r == null) {
                                    if (nv == null) "printer unreachable" else "no prints on record"
                                } else ""
                                val u = r?.thumbUrl
                                if (u != null) {
                                    if (u != lastRecordThumb) fetchBytes(u)?.let { b ->
                                        BitmapFactory.decodeByteArray(b, 0, b.size)?.let {
                                            thumb = it; lastRecordThumb = u
                                        }
                                    }
                                } else if (r?.name != null && r.name != lanTriedFor) {
                                    // No attachment (a record from before thumbnails, or a failed
                                    // upload): while we are on the LAN the file is usually still
                                    // on the printer's own storage, so ask it directly. Once per
                                    // record — a miss must not re-ask every five seconds.
                                    lanTriedFor = r.name
                                    thumb = lanThumb(r.name)
                                } else if (r?.name == null) thumb = null
                            }
                        }
                        delay(5_000)
                    }
                }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(40.dp))
                    Text("prusa", fontSize = 13.sp, color = HEADER_COLOR)
                    if (status.isNotEmpty()) { Spacer(Modifier.height(20.dp)); Text(status, fontSize = 12.sp) }
                    // Only while a print is running: between prints the same picture belongs to
                    // the record below, and drawing it here as well printed it twice.
                    if (v?.active == true) thumb?.let {
                        Spacer(Modifier.height(6.dp))
                        Image(
                            it.asImageBitmap(), contentDescription = "print preview",
                            modifier = Modifier.fillMaxWidth().height(105.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    v?.takeIf { it.active }?.let { p ->
                        p.displayName?.let {
                            Text(cleanName(it), fontSize = 12.sp, color = Color.White, maxLines = 1)
                        }
                        Spacer(Modifier.height(4.dp))
                        val headState = (p.stateText ?: p.state).lowercase()
                        Text(headState + (p.progress?.let { " · $it%" } ?: ""), fontSize = 16.sp, color = ACCENT)
                        if (p.printingS != null && p.remainingS != null) {
                            Spacer(Modifier.height(6.dp))
                            Timeline(p.printingS, p.remainingS)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (p.nozzle != null || p.bed != null) {
                            Row(Modifier.fillMaxWidth()) {
                                Text("temp", fontSize = 12.sp, color = HEADER_COLOR, modifier = Modifier.weight(1f))
                                val noz = p.nozzle?.let { "nozzle ${it.toInt()}°" +
                                    (p.nozzleTarget?.takeIf { t -> t > 0 }?.let { t -> "/${t.toInt()}°" } ?: "") }
                                val bed = p.bed?.let { "bed ${it.toInt()}°" +
                                    (p.bedTarget?.takeIf { t -> t > 0 }?.let { t -> "/${t.toInt()}°" } ?: "") }
                                Text(listOfNotNull(noz, bed).joinToString("  "), fontSize = 12.sp, color = Color.White)
                            }
                        }
                        p.material?.let {
                            Row(Modifier.fillMaxWidth()) {
                                Text("material", fontSize = 12.sp, color = HEADER_COLOR, modifier = Modifier.weight(1f))
                                Text(it, fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                    if (v?.active != true) record?.let { r -> RecordFrame(r, thumb) }
                    Spacer(Modifier.height(70.dp))
                }
            }
        }
    }

    /** The printer's own copy of the picture, matched by name against its storage listing.
     *  LAN only by nature — off the home network this returns null and the record shows without
     *  a picture, which is why the poller attaches one to the completion message as well. */
    private fun lanThumb(recordName: String): android.graphics.Bitmap? = runCatching {
        val listing = PrusaClient.getText("/api/v1/files/usb") ?: return null
        val kids = Json.parseToJsonElement(listing).jsonObject["children"]?.jsonArray ?: return null
        // The record name is the display name clipped to 40 characters by the poller, so match
        // by prefix rather than equality.
        val key = recordName.trim().lowercase()
        val ref = kids.firstNotNullOfOrNull { e ->
            val o = e.jsonObject
            val d = o["display_name"]?.jsonPrimitive?.content?.lowercase()
            if (d != null && (d.startsWith(key) || key.startsWith(d.substringBeforeLast('.'))))
                o["refs"]?.jsonObject?.get("thumbnail")?.jsonPrimitive?.content else null
        } ?: return null
        PrusaClient.get(ref)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }.getOrNull()

    /** Any URL on the bus, carrying the token when one is configured. */
    private fun fetchBytes(url: String): ByteArray? = runCatching {
        NtfyClient.http.newCall(Request.Builder().url(url).build()).execute()
            .use { if (it.isSuccessful) it.body.bytes() else null }
    }.getOrNull()

    /** Newest done/stopped record on the printer topic; null when the bus has none. */
    private fun lastRecord(): PrintRecord? = runCatching {
        val url = "${NtfyClient.baseUrl}/${BuildConfig.TOPIC_PRINTER}/json?poll=1&since=720h"
        val body = NtfyClient.http.newCall(Request.Builder().url(url).build()).execute()
            .use { if (it.isSuccessful) it.body.string() else "" }
        var best: PrintRecord? = null
        for (line in body.lines().filter { it.isNotBlank() }) {
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["event"]?.jsonPrimitive?.content != "message") continue
            val t = o["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            val msg = o["message"]?.jsonPrimitive?.content?.trim() ?: continue
            val parts = msg.split("\u00b7").map { it.trim() }
            val verb = parts.firstOrNull()?.lowercase() ?: continue
            if (verb != "done" && verb != "stopped") continue
            val prev = best
            if (prev != null && t < prev.epochS) continue
            best = PrintRecord(
                finished = verb == "done",
                name = parts.getOrNull(1)?.takeIf { it.isNotEmpty() && it != "?" },
                duration = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
                percent = parts.getOrNull(3)?.removeSuffix("%")?.toIntOrNull(),
                epochS = t,
                // The poller attaches the job's own thumbnail to the completion message, so the
                // picture outlives the job on the printer and travels off the LAN with the record.
                thumbUrl = o["attachment"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
            )
        }
        best
    }.getOrNull()

    private fun fetch(): PrinterView? {
        val st = PrusaClient.getText("/api/v1/status") ?: return null
        val so = runCatching { Json.parseToJsonElement(st).jsonObject }.getOrNull() ?: return null
        val printer = so["printer"]?.jsonObject
        val job = so["job"]?.jsonObject
        fun d(o: kotlinx.serialization.json.JsonObject?, k: String) =
            o?.get(k)?.jsonPrimitive?.content?.toDoubleOrNull()
        var displayName: String? = null; var thumbPath: String? = null
        if (job != null) {
            PrusaClient.getText("/api/v1/job")?.let { jt ->
                runCatching { Json.parseToJsonElement(jt).jsonObject }.getOrNull()?.let { jo ->
                    val file = jo["file"]?.jsonObject
                    displayName = file?.get("display_name")?.jsonPrimitive?.content
                    thumbPath = file?.get("refs")?.jsonObject?.get("thumbnail")?.jsonPrimitive?.content
                }
            }
        }
        // Legacy endpoint: the human-readable state ("Absorbing heat") and the loaded material.
        var stateText: String? = null; var material: String? = null
        PrusaClient.getText("/api/printer")?.let { lt ->
            runCatching { Json.parseToJsonElement(lt).jsonObject }.getOrNull()?.let { lo ->
                stateText = lo["state"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                material = lo["telemetry"]?.jsonObject?.get("material")?.jsonPrimitive?.content
            }
        }
        return PrinterView(
            state = printer?.get("state")?.jsonPrimitive?.content ?: "?",
            stateText = stateText,
            progress = d(job, "progress")?.toInt(),
            remainingS = d(job, "time_remaining")?.toLong(),
            printingS = d(job, "time_printing")?.toLong(),
            nozzle = d(printer, "temp_nozzle"), nozzleTarget = d(printer, "target_nozzle"),
            bed = d(printer, "temp_bed"), bedTarget = d(printer, "target_bed"),
            material = material, displayName = displayName, thumbPath = thumbPath,
        )
    }

    private fun cleanName(n: String): String = n.substringBeforeLast('.').replace('_', ' ')
}

/** Start time on the left, total expected duration on the right, remaining trailing the fill. */
@Composable
private fun Timeline(printingS: Long, remainingS: Long) {
    val totalS = (printingS + remainingS).coerceAtLeast(1)
    val frac = (printingS.toFloat() / totalS).coerceIn(0f, 1f)
    val started = Instant.now().minusSeconds(printingS).atZone(ZoneId.systemDefault())
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(started.format(DateTimeFormatter.ofPattern("H:mm")), fontSize = 11.sp, color = HEADER_COLOR,
                modifier = Modifier.weight(1f))
            Text(fmtDur(totalS), fontSize = 11.sp, color = HEADER_COLOR)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(BAR_TRACK, RoundedCornerShape(4.dp))) {
            Box(
                Modifier.fillMaxWidth(frac.coerceAtLeast(0.02f)).height(8.dp)
                    .background(ACCENT, RoundedCornerShape(4.dp))
            )
        }
        // Remaining, trailing the fill edge (clamped so it never falls off the sides).
        Row(Modifier.fillMaxWidth()) {
            if (frac > 0.05f) Spacer(Modifier.weight(frac.coerceAtMost(0.75f)))
            Text("${fmtDur(remainingS)} left", fontSize = 11.sp, color = ACCENT)
            Spacer(Modifier.weight((1f - frac).coerceAtLeast(0.05f)))
        }
    }
}

private fun fmtDur(s: Long): String {
    val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Between prints: what the last one was, that it reached 100 %, and when it finished. */
@Composable
private fun RecordFrame(r: PrintRecord, thumb: android.graphics.Bitmap?) {
    val at = Instant.ofEpochSecond(r.epochS).atZone(ZoneId.systemDefault())
    val sameDay = at.toLocalDate() == java.time.LocalDate.now(ZoneId.systemDefault())
    val stamp = at.format(DateTimeFormatter.ofPattern(if (sameDay) "H:mm" else "EEE H:mm"))
    val pct = r.percent ?: if (r.finished) 100 else 0
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        Text("last print", fontSize = 12.sp, color = HEADER_COLOR)
        thumb?.let {
            Spacer(Modifier.height(4.dp))
            Image(
                it.asImageBitmap(), contentDescription = "last print preview",
                modifier = Modifier.fillMaxWidth().height(105.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            r.name?.substringBeforeLast('.')?.replace('_', ' ') ?: "unnamed",
            fontSize = 13.sp, color = Color.White, maxLines = 2,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            (if (r.finished) "done" else "stopped") + " \u00b7 " + pct + "%",
            fontSize = 16.sp, color = if (r.finished) ACCENT else Color(0xFFB0B6C0),
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).background(BAR_TRACK, RoundedCornerShape(4.dp))) {
            Box(
                Modifier.fillMaxWidth((pct / 100f).coerceIn(0.02f, 1f)).height(8.dp)
                    .background(if (r.finished) ACCENT else Color(0xFF6B7280), RoundedCornerShape(4.dp))
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("finished", fontSize = 12.sp, color = HEADER_COLOR, modifier = Modifier.weight(1f))
            Text(stamp, fontSize = 12.sp, color = Color.White)
        }
        Row(Modifier.fillMaxWidth()) {
            Text("ago", fontSize = 12.sp, color = HEADER_COLOR, modifier = Modifier.weight(1f))
            Text(relAge(r.epochS), fontSize = 12.sp, color = Color.White)
        }
        r.duration?.let {
            Row(Modifier.fillMaxWidth()) {
                Text("took", fontSize = 12.sp, color = HEADER_COLOR, modifier = Modifier.weight(1f))
                Text(it, fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

private fun relAge(epochS: Long): String {
    val mins = (Instant.now().epochSecond - epochS) / 60
    return when {
        mins < 1 -> "just now"
        mins < 60 -> mins.toString() + "m"
        mins < 60 * 24 -> (mins / 60).toString() + "h " + (mins % 60) + "m"
        else -> (mins / (60 * 24)).toString() + "d " + ((mins % (60 * 24)) / 60) + "h"
    }
}
