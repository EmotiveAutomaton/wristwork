package com.emotiveautomaton.wristwork.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.emotiveautomaton.wristwork.BuildConfig
import com.emotiveautomaton.wristwork.net.NtfyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** One reading of the rig: epoch seconds + integer percents / temperatures. */
private data class Reading(
    val t: Long, val cpu: Int?, val gpu: Int?, val ram: Int?, val vram: Int?,
    val tg: Int?, val tc: Int?,
)

/** One process row: name plus its own cpu/gpu/ram percent of the whole machine. */
private data class Proc(val name: String, val c: Int, val g: Int, val r: Int)

/** Series palette — shared by graphs and the table's number tinting. */
private val CPU_COLOR = Color(0xFF7FB5FF)
private val GPU_COLOR = Color(0xFF7FE08A)
private val RAM_COLOR = Color(0xFFFFB36B)
private val VRAM_COLOR = Color(0xFFC9A0FF)
private val TEMP_GPU_COLOR = Color(0xFFFF8080)
private val TEMP_CPU_COLOR = Color(0xFFFFD066)
private val HEADER_COLOR = Color(0xFF9EB8D8)

/**
 * The rig tap-frame: five stacked 6-hour charts (cpu/gpu/ram/vram percent, then all
 * temperatures superimposed on one 0-100 degC chart), then the top-10 processes with c/g/r
 * columns whose numbers shade from white toward the series color as they approach 99.
 * History reads straight off the bus cache; nothing new is stored. Back-swipe closes.
 */
class RigDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var readings by remember { mutableStateOf<List<Reading>>(emptyList()) }
                var procs by remember { mutableStateOf<List<Proc>>(emptyList()) }
                var status by remember { mutableStateOf("loading…") }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        runCatching { fetch() }
                            .onSuccess { (r, p) ->
                                readings = r; procs = p
                                status = if (r.isEmpty()) "no data in the last 6h" else ""
                            }
                            .onFailure { status = "fetch failed" }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(44.dp))
                    Text("rig · 6h", fontSize = 13.sp, color = HEADER_COLOR)
                    if (status.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp)); Text(status, fontSize = 12.sp)
                    }
                    Graph("cpu", readings.mapNotNull { r -> r.cpu?.let { r.t to it } }, CPU_COLOR)
                    Graph("gpu", readings.mapNotNull { r -> r.gpu?.let { r.t to it } }, GPU_COLOR)
                    Graph("ram", readings.mapNotNull { r -> r.ram?.let { r.t to it } }, RAM_COLOR)
                    Graph("vram", readings.mapNotNull { r -> r.vram?.let { r.t to it } }, VRAM_COLOR)
                    MultiGraph(
                        "temp °C",
                        listOf(
                            Triple("gpu", TEMP_GPU_COLOR, readings.mapNotNull { r -> r.tg?.let { r.t to it } }),
                            Triple("cpu", TEMP_CPU_COLOR, readings.mapNotNull { r -> r.tc?.let { r.t to it } }),
                        ).filter { it.third.isNotEmpty() },
                    )
                    Spacer(Modifier.height(10.dp))
                    if (procs.isNotEmpty()) ProcHeader()
                    procs.forEach { ProcRow(it) }
                    // Round screen: the bottom of the scroll needs clearance to clear the arc.
                    Spacer(Modifier.height(70.dp))
                }
            }
        }
    }

    /** Pull the last 6 h of rig messages off the bus; newest message with procs feeds the list. */
    private fun fetch(): Pair<List<Reading>, List<Proc>> {
        val url = "${NtfyClient.baseUrl}/${BuildConfig.TOPIC_RIG}/json?poll=1&since=6h"
        val body = NtfyClient.http.newCall(Request.Builder().url(url).build()).execute()
            .use { if (it.isSuccessful) it.body.string() else "" }
        val readings = mutableListOf<Reading>()
        var procs: List<Proc> = emptyList()
        for (line in body.lines().filter { it.isNotBlank() }) {
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["event"]?.jsonPrimitive?.content != "message") continue
            val t = o["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            val m = runCatching {
                Json.parseToJsonElement(o["message"]!!.jsonPrimitive.content).jsonObject
            }.getOrNull() ?: continue
            fun num(k: String) = m[k]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()
            fun pct(k: String) = num(k)?.coerceIn(0, 99)
            readings += Reading(t, pct("cpu"), pct("gpu"), pct("ram"), pct("vram"), num("tg"), num("tc"))
            m["procs"]?.let { pl ->
                runCatching {
                    procs = pl.jsonArray.map { e ->
                        val a = e.jsonArray
                        fun v(i: Int) = a.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull()
                            ?.toInt()?.coerceIn(0, 99) ?: 0
                        Proc(a[0].jsonPrimitive.content, v(1), v(2), v(3))
                    }
                }
            }
        }
        return readings.sortedBy { it.t } to procs
    }
}

/** Header row: blank name column then c/g/r labels aligned over their number columns. */
@Composable
private fun ProcHeader() {
    Row(Modifier.fillMaxWidth()) {
        Cell("", Color.Transparent, weightRow = true)
        Cell("c", HEADER_COLOR); Cell("g", HEADER_COLOR); Cell("r", HEADER_COLOR)
    }
}

/** Name in white; each number shading white -> its series color as it approaches 99. */
@Composable
private fun ProcRow(p: Proc) {
    fun heat(v: Int, series: Color) = lerp(Color.White, series, (v / 99f).coerceIn(0f, 1f))
    Row(Modifier.fillMaxWidth()) {
        Cell(p.name.take(12), Color.White, weightRow = true)
        Cell("${p.c}", heat(p.c, CPU_COLOR))
        Cell("${p.g}", heat(p.g, GPU_COLOR))
        Cell("${p.r}", heat(p.r, RAM_COLOR))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String, color: Color, weightRow: Boolean = false,
) {
    Text(
        if (weightRow) text else text.padStart(3),
        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color, maxLines = 1,
        modifier = if (weightRow) Modifier.weight(1f) else Modifier,
    )
}

/** One labeled 0-100 line chart. X spans the fetched window; baselines at 0/50/100. */
@Composable
private fun Graph(label: String, points: List<Pair<Long, Int>>, color: Color) {
    MultiGraph(label, if (points.isEmpty()) emptyList() else listOf(Triple(label, color, points)), showSeriesLabels = false)
}

/** Several series superimposed on one 0-100 chart, with per-series colored values at top right. */
@Composable
private fun MultiGraph(
    label: String,
    series: List<Triple<String, Color, List<Pair<Long, Int>>>>,
    showSeriesLabels: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 11.sp, color = series.firstOrNull()?.second ?: HEADER_COLOR,
                modifier = Modifier.weight(1f))
            series.forEach { (name, color, pts) ->
                val v = pts.lastOrNull()?.second?.toString() ?: "–"
                Text(if (showSeriesLabels && series.size > 1) " $name $v" else v,
                    fontSize = 11.sp, color = color)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val grid = Color(0x33FFFFFF)
            for (frac in listOf(0f, 0.5f, 1f)) {
                drawLine(grid, Offset(0f, size.height * frac), Offset(size.width, size.height * frac), 1f)
            }
            val allT = series.flatMap { it.third.map { p -> p.first } }
            if (allT.size < 2) return@Canvas
            val t0 = allT.min(); val t1 = allT.max().coerceAtLeast(t0 + 1)
            series.forEach { (_, color, pts) ->
                if (pts.size < 2) return@forEach
                val path = Path()
                pts.forEachIndexed { i, (t, v) ->
                    val x = (t - t0).toFloat() / (t1 - t0) * size.width
                    val y = size.height * (1f - (v.coerceIn(0, 100)) / 100f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}
