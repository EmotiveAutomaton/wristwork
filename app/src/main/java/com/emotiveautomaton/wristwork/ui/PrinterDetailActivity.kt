package com.emotiveautomaton.wristwork.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.emotiveautomaton.wristwork.net.PrusaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val HEADER_COLOR = Color(0xFF9EB8D8)
private val ACCENT = Color(0xFFFFB36B)

private data class PrinterView(
    val state: String, val progress: Int?, val remainingS: Long?, val printingS: Long?,
    val nozzle: Double?, val nozzleTarget: Double?, val bed: Double?, val bedTarget: Double?,
    val z: Double?, val speed: Int?, val fanHotend: Int?, val fanPrint: Int?,
    val displayName: String?, val thumbPath: String?,
)

/**
 * The printer tap-frame: the job's own embedded thumbnail (the picture of what's printing),
 * then a rolling status — progress, times, temps, z-height, speed, fans — refreshed every 5 s
 * while the frame is open. Talks to PrusaLink directly over the LAN with digest auth.
 */
class PrinterDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    v?.let { p ->
                        p.displayName?.let {
                            Text(cleanName(it), fontSize = 12.sp, color = Color.White, maxLines = 2)
                        }
                        Spacer(Modifier.height(4.dp))
                        val head = p.state.lowercase() +
                            (p.progress?.let { " · $it%" } ?: "")
                        Text(head, fontSize = 16.sp, color = ACCENT)
                        p.remainingS?.let { Line("remaining", fmtDur(it)) }
                        p.printingS?.let { Line("elapsed", fmtDur(it)) }
                        if (p.nozzle != null) Line("nozzle", "${p.nozzle.toInt()}°" +
                            (p.nozzleTarget?.let { "/${it.toInt()}°" } ?: ""))
                        if (p.bed != null) Line("bed", "${p.bed.toInt()}°" +
                            (p.bedTarget?.let { "/${it.toInt()}°" } ?: ""))
                        p.z?.let { Line("z", "${it} mm") }
                        p.speed?.let { Line("speed", "$it%") }
                        p.fanHotend?.let { Line("fan hotend", "$it rpm") }
                        p.fanPrint?.let { Line("fan print", "$it rpm") }
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
        // Job detail (file name + thumbnail ref) only exists while a job is active.
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
        return PrinterView(
            state = printer?.get("state")?.jsonPrimitive?.content ?: "?",
            progress = d(job, "progress")?.toInt(),
            remainingS = d(job, "time_remaining")?.toLong(),
            printingS = d(job, "time_printing")?.toLong(),
            nozzle = d(printer, "temp_nozzle"), nozzleTarget = d(printer, "target_nozzle"),
            bed = d(printer, "temp_bed"), bedTarget = d(printer, "target_bed"),
            z = d(printer, "axis_z"), speed = d(printer, "speed")?.toInt(),
            fanHotend = d(printer, "fan_hotend")?.toInt(), fanPrint = d(printer, "fan_print")?.toInt(),
            displayName = displayName, thumbPath = thumbPath,
        )
    }

    /** "Benchy_Rules_0.4n_0.2mm_PLA_COREONE_14m.bgcode" -> "Benchy Rules" (settings stay on the line below). */
    private fun cleanName(n: String): String = n.substringBeforeLast('.').replace('_', ' ')

    private fun fmtDur(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m ${s % 60}s"
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = HEADER_COLOR, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White)
    }
}
