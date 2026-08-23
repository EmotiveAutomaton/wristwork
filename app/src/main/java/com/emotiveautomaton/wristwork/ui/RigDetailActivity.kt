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

/** One reading of the rig: epoch seconds + integer percents (0-99). */
private data class Reading(val t: Long, val cpu: Int?, val gpu: Int?, val ram: Int?)

/** One process row: name plus its own cpu/gpu/ram percent of the whole machine. */
private data class Proc(val name: String, val c: Int, val g: Int, val r: Int)

/** Fixed-width row so the three number columns align down the list (8-char name, 3 x 3-char). */
private fun procLine(name: String, c: String, g: String, r: String): String =
    name.take(8).padEnd(8) + c.padStart(3) + g.padStart(3) + r.padStart(3)

/**
 * The rig tap-frame: three 6-hour percent graphs (cpu/gpu/ram) stacked top-down, then the
 * latest top-processes list, all in one scrollable column. History comes straight from the
 * bus's message cache (`?poll=1&since=6h`) — nothing new is stored anywhere.
 * Back gesture (swipe right) closes it; this is a single frame, not a navigation tree.
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
                        .padding(horizontal = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(30.dp))
                    Text("rig · 6h", fontSize = 13.sp, color = Color(0xFF9EB8D8))
                    if (status.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp)); Text(status, fontSize = 12.sp)
                    }
                    Graph("cpu", readings.mapNotNull { r -> r.cpu?.let { r.t to it } }, Color(0xFF7FB5FF))
                    Graph("gpu", readings.mapNotNull { r -> r.gpu?.let { r.t to it } }, Color(0xFF7FE08A))
                    Graph("ram", readings.mapNotNull { r -> r.ram?.let { r.t to it } }, Color(0xFFFFB36B))
                    Spacer(Modifier.height(10.dp))
                    if (procs.isNotEmpty()) {
                        Text(procLine("", "c", "g", "r"), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, color = Color(0xFF9EB8D8))
                    }
                    procs.forEach { pr ->
                        Text(procLine(pr.name, "${pr.c}", "${pr.g}", "${pr.r}"),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
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
            fun pct(k: String) = m[k]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()?.coerceIn(0, 99)
            readings += Reading(t, pct("cpu"), pct("gpu"), pct("ram"))
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

/** One labeled 0–100% line graph. X spans the fetched window, dotted baselines at 0/50/100. */
@androidx.compose.runtime.Composable
private fun Graph(label: String, points: List<Pair<Long, Int>>, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 11.sp, color = color, modifier = Modifier.weight(1f))
            Text(points.lastOrNull()?.second?.toString() ?: "–", fontSize = 11.sp, color = color)
        }
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val grid = Color(0x33FFFFFF)
            for (frac in listOf(0f, 0.5f, 1f)) {
                drawLine(grid, Offset(0f, size.height * frac), Offset(size.width, size.height * frac), 1f)
            }
            if (points.size >= 2) {
                val t0 = points.first().first
                val t1 = points.last().first.coerceAtLeast(t0 + 1)
                val path = Path()
                points.forEachIndexed { i, (t, v) ->
                    val x = (t - t0).toFloat() / (t1 - t0) * size.width
                    val y = size.height * (1f - v / 100f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}
