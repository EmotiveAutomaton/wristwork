package com.emotiveautomaton.wristwork.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.emotiveautomaton.wristwork.BuildConfig
import com.emotiveautomaton.wristwork.net.NtfyClient
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** One finished-project entry: latest completion per project, newest first. */
private data class Finish(val project: String, val epochS: Long, val desc: String?)

/**
 * The agents tap-frame: the most recent finish per project (deduped — three SoundingLine
 * finishes collapse to the newest one), newest first, as tappable chips. Tapping a chip asks
 * the paired phone to open Claude Code (claude.ai/code — the Claude app intercepts it if
 * installed; the browser otherwise). Session-level deep links don't exist publicly, so the
 * chip opens the Claude Code area, not the specific conversation.
 */
class AgentsDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WristTheme {
                var finishes by remember { mutableStateOf<List<Finish>>(emptyList()) }
                var status by remember { mutableStateOf("loading…") }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        runCatching { fetch() }
                            .onSuccess { finishes = it; status = if (it.isEmpty()) "no finishes yet" else "" }
                            .onFailure { status = "fetch failed" }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(40.dp))
                    Text("agents · 24h", fontSize = 13.sp, color = Color(0xFF9EB8D8))
                    Spacer(Modifier.height(8.dp))
                    if (status.isNotEmpty()) Text(status, fontSize = 12.sp)
                    finishes.forEach { f ->
                        Spacer(Modifier.height(6.dp))
                        Chip(
                            onClick = { openClaudeOnPhone() },
                            label = { Text("${f.project} \u00b7 ${relAge(f.epochS)}", fontSize = 13.sp, maxLines = 1) },
                            secondaryLabel = f.desc?.let { d ->
                                { Text(d, fontSize = 10.sp, maxLines = 2) }
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(70.dp))
                }
            }
        }
    }

    /** Latest `done: {project}` per project off the bus cache, newest first. */
    private fun fetch(): List<Finish> {
        val url = "${NtfyClient.baseUrl}/${BuildConfig.TOPIC_AGENTS}/json?poll=1&since=720h"
        val body = NtfyClient.http.newCall(Request.Builder().url(url).build()).execute()
            .use { if (it.isSuccessful) it.body.string() else "" }
        val latest = LinkedHashMap<String, Finish>()
        for (line in body.lines().filter { it.isNotBlank() }) {
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["event"]?.jsonPrimitive?.content != "message") continue
            val t = o["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
            val msg = o["message"]?.jsonPrimitive?.content?.trim() ?: continue
            // "done: project" or "done: project: what happened"
            val m = Regex("^done:\\s*([^:]+?)(?::\\s*(.+))?$", RegexOption.IGNORE_CASE).find(msg) ?: continue
            val project = m.groupValues[1].trim()
            val desc = m.groupValues.getOrNull(2)?.trim()?.ifEmpty { null }
            if (t >= (latest[project]?.epochS ?: 0)) latest[project] = Finish(project, t, desc)
        }
        val all = latest.values.sortedByDescending { it.epochS }
        // Last 24 h; never empty — fall back to the single most recent finish however old.
        val dayAgo = java.time.Instant.now().epochSecond - 24 * 3600
        val recent = all.filter { it.epochS >= dayAgo }
        return if (recent.isNotEmpty()) recent else all.take(1)
    }

    private fun relAge(epochS: Long): String {
        val mins = (Instant.now().epochSecond - epochS) / 60
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }

    /** Fire-and-forget: ask the paired phone to open Claude Code. */
    private fun openClaudeOnPhone() {
        runCatching {
            RemoteActivityHelper(this).startRemoteActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/code"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            )
        }
    }
}
