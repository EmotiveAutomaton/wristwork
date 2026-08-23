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
import com.emotiveautomaton.wristwork.net.PrusaClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
)

/**
 * The printer tap-frame v2: thumbnail, print name, `state · NN%` headline (the state text is the
 * detailed one — "absorbing heat" — when PrusaLink offers it), a timeline bar (start time on the
 * left, total expected duration on the right, remaining following the fill), one temperature
 * line, material. Refreshes every 5 s while open.
 */
class PrinterDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WristTheme {
                var v by remember { mutableStateOf<PrinterView?>(null) }
                var thumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                var status by remember { mutableStateOf("loading…") }
                LaunchedEffect(Unit) {
                    var lastThumbPath: String? = null
                    while (true) {
                        withContext(Dispatchers.IO) {
                            val nv = fetch()
                            if (nv != null) {
                                v = nv; status = ""
                                if (nv.thumbPath != null && nv.thumbPath != lastThumbPath) {
                                    PrusaClient.get(nv.thumbPath)?.let { bytes ->
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                                            thumb = it; lastThumbPath = nv.thumbPath
                                        }
                                    }
                                }
                            } else if (v == null) status = "printer unreachable"
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
                    thumb?.let {
                        Spacer(Modifier.height(6.dp))
                        Image(
                            it.asImageBitmap(), contentDescription = "print preview",
                            modifier = Modifier.fillMaxWidth().height(105.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    v?.let { p ->
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
                    Spacer(Modifier.height(70.dp))
                }
            }
        }
    }

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
