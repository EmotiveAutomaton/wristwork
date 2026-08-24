package com.emotiveautomaton.wristwork.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.emotiveautomaton.wristwork.complication.StateComplicationService
import com.emotiveautomaton.wristwork.data.CurrentState
import com.emotiveautomaton.wristwork.data.StateNames
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.data.TagEvent
import com.emotiveautomaton.wristwork.work.DrainWorker
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HEADER_COLOR = Color(0xFF9EB8D8)
private val ACCENT = Color(0xFFFFB36B)
private val CHIP_BG = Color(0xFF2A2F3A)

/** One timeline entry: a labeled event (latest revision) or an unlabeled body-response flag. */
private data class TimelineItem(
    val time: OffsetDateTime,
    val label: String,        // humane display or "flag"
    val eventId: String?,     // set for label events (relabel target)
    val flagId: Long?,        // set for flags (label-this target)
)

/**
 * Grid v2 (HEALTH_DESIGN.md UX contract). Fast path unchanged: single tap on a state = primary
 * only, submit, auto-close. Long-press a state = elaborate mode: that state is dominant,
 * further taps toggle secondaries, sliders (intensity/confidence) and confirm appear.
 * Timeline strip on top: last events + flags; tap = relabel/label-that; the `+` chip creates a
 * retro event whose time is set with 15-min arrows (position-scrubbing was rejected as too
 * imprecise). Backing out discards — that is the whole undo. Every submit is an APPEND;
 * revisions carry the same eventId + a `revises` pointer. Mic note at the very bottom.
 */
class TagActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingNote: String? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            pendingNote = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cueFlagRef = intent.getLongExtra(EXTRA_FLAG_REF, -1L).takeIf { it >= 0 }
        setContent {
            WristTheme {
                var noticedBefore by remember { mutableStateOf(false) }
                var dominant by remember { mutableStateOf<String?>(null) }   // null = fast mode
                var secondaries by remember { mutableStateOf(setOf<String>()) }
                var intensity by remember { mutableStateOf(0f) }             // 0 = unset, 1..5
                var confidence by remember { mutableStateOf(0f) }
                var timeline by remember { mutableStateOf<List<TimelineItem>>(emptyList()) }
                var relabelOf by remember { mutableStateOf<TagEvent?>(null) }
                var labelFlagId by remember { mutableStateOf(cueFlagRef) }
                var retroMinutes by remember { mutableStateOf<Int?>(null) }  // minutes ago, 15-step

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        val db = TagDb.get(applicationContext)
                        val events = db.tags().latestEvents(3).map {
                            TimelineItem(OffsetDateTime.parse(it.tsEvent),
                                StateNames.humane(it.primaryState), it.eventId, null)
                        }
                        val flags = db.flags().latestBodyResponses(2).map {
                            TimelineItem(OffsetDateTime.parse(it.ts), "flag", null, it.id)
                        }
                        timeline = (events + flags).sortedByDescending { it.time }.take(3)
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Spacer(Modifier.height(26.dp))

                    // ---- timeline strip ----
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        timeline.forEach { item ->
                            val selected = (item.eventId != null && item.eventId == relabelOf?.eventId) ||
                                (item.flagId != null && item.flagId == labelFlagId)
                            Text(
                                "${item.time.format(DateTimeFormatter.ofPattern("H:mm"))} ${item.label}",
                                fontSize = 10.sp,
                                color = if (selected) ACCENT else HEADER_COLOR,
                                modifier = Modifier
                                    .background(CHIP_BG, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                    .combinedClickable(onClick = {
                                        if (item.eventId != null) {
                                            appScope.launch {
                                                relabelOf = TagDb.get(applicationContext).tags().currentOf(item.eventId)
                                            }
                                            labelFlagId = null; retroMinutes = null
                                        } else if (item.flagId != null) {
                                            labelFlagId = item.flagId; relabelOf = null; retroMinutes = null
                                        }
                                    }),
                            )
                        }
                        // retro event creator
                        Text(
                            "+", fontSize = 12.sp, color = ACCENT,
                            modifier = Modifier.background(CHIP_BG, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .combinedClickable(onClick = {
                                    retroMinutes = 15; relabelOf = null; labelFlagId = null
                                }),
                        )
                    }

                    // ---- mode banner / retro time arrows ----
                    relabelOf?.let {
                        Text("relabeling ${it.tsEvent.substring(11, 16)} ${StateNames.humane(it.primaryState)}",
                            fontSize = 10.sp, color = ACCENT)
                    }
                    if (labelFlagId != null) Text("labeling flagged moment", fontSize = 10.sp, color = ACCENT)
                    retroMinutes?.let { mins ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("<", fontSize = 18.sp, color = ACCENT,
                                modifier = Modifier.combinedClickable(onClick = { retroMinutes = mins + 15 })
                                    .padding(6.dp))
                            Text(
                                OffsetDateTime.now().minusMinutes(mins.toLong())
                                    .format(DateTimeFormatter.ofPattern("H:mm")),
                                fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center,
                            )
                            Text(">", fontSize = 18.sp, color = ACCENT,
                                modifier = Modifier.combinedClickable(
                                    onClick = { retroMinutes = (mins - 15).coerceAtLeast(0) })
                                    .padding(6.dp))
                        }
                    }

                    ToggleChip(
                        checked = noticedBefore,
                        onCheckedChange = { noticedBefore = it },
                        label = { Text("noticed before?", fontSize = 11.sp) },
                        toggleControl = { Icon(ToggleChipDefaults.switchIcon(checked = noticedBefore), null) },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                    )

                    // ---- grid 3/3/2 ----
                    StateNames.CANONICAL.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { canon ->
                                val isDominant = dominant == canon
                                val isSecondary = canon in secondaries
                                Button(
                                    onClick = {
                                        when {
                                            dominant == null ->
                                                commit(canon, emptySet(), noticedBefore, null, null,
                                                    relabelOf, labelFlagId, retroMinutes)
                                            dominant == "" -> dominant = canon   // armed: set dominant
                                            isDominant -> {}   // tap on dominant: no-op
                                            else -> secondaries =
                                                if (isSecondary) secondaries - canon else secondaries + canon
                                        }
                                    },
                                    modifier = Modifier.size(width = 62.dp, height = 38.dp),
                                    colors = when {
                                        isDominant -> ButtonDefaults.primaryButtonColors()
                                        isSecondary -> ButtonDefaults.secondaryButtonColors(
                                            contentColor = ACCENT)
                                        else -> ButtonDefaults.secondaryButtonColors()
                                    },
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(StateNames.humane(canon), fontSize = 12.sp)
                                        Text(canon.lowercase(), fontSize = 7.sp, color = HEADER_COLOR)
                                    }
                                }
                            }
                        }
                    }
                    // long-press entry into elaborate mode: a dedicated row (Buttons swallow
                    // long-press unreliably across faces; an explicit toggle is the honest control)
                    if (dominant == null) {
                        Text(
                            "mix…", fontSize = 11.sp, color = HEADER_COLOR,
                            modifier = Modifier.combinedClickable(onClick = {
                                dominant = ""   // armed: next tap sets dominant
                            }).padding(4.dp),
                        )
                    }
                    if (dominant == "") Text("tap the dominant state", fontSize = 10.sp, color = ACCENT)

                    // ---- elaborate mode extras ----
                    if (!dominant.isNullOrEmpty()) {
                        SliderRow("intensity", intensity) { intensity = it }
                        SliderRow("confidence", confidence) { confidence = it }
                        Button(
                            onClick = {
                                commit(dominant!!, secondaries, noticedBefore,
                                    intensity.toInt().takeIf { it > 0 },
                                    confidence.toInt().takeIf { it > 0 },
                                    relabelOf, labelFlagId, retroMinutes)
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                        ) { Text("confirm", fontSize = 12.sp) }
                    }

                    Button(
                        onClick = {
                            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                        },
                        modifier = Modifier.size(width = 70.dp, height = 32.dp),
                    ) { Text("mic note", fontSize = 10.sp) }
                    Spacer(Modifier.height(40.dp))
                }

            }
        }
    }

    /** Every submit APPENDS. Revisions share eventId and point at the row they supersede. */
    private fun commit(
        primary: String, secondaries: Set<String>, noticedBefore: Boolean,
        intensity: Int?, confidence: Int?,
        relabelOf: TagEvent?, labelFlagId: Long?, retroMinutes: Int?,
    ) {
        // armed sentinel: first tap in "mix…" mode selects the dominant instead of committing
        if (primary.isEmpty()) return
        val now = OffsetDateTime.now()
        val tsEvent = when {
            relabelOf != null -> relabelOf.tsEvent
            retroMinutes != null -> now.minusMinutes(retroMinutes.toLong())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            else -> now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        val source = when {
            relabelOf != null -> relabelOf.source
            labelFlagId != null -> "fitbit-flag"
            retroMinutes != null -> "timeline-retro"
            else -> "manual"
        }
        val note = pendingNote
        val ctx = applicationContext
        appScope.launch {
            val db = TagDb.get(ctx)
            db.tags().insert(TagEvent(
                eventId = relabelOf?.eventId ?: UUID.randomUUID().toString(),
                tsEvent = tsEvent,
                tsEntered = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                primaryState = primary,
                secondaries = secondaries.joinToString(","),
                intensity = intensity, confidence = confidence,
                noticedBefore = noticedBefore, note = note, source = source,
                flagRef = labelFlagId?.toString(), revises = relabelOf?.id,
            ))
            if (relabelOf == null && retroMinutes == null) {
                CurrentState.write(ctx, primary, now.toInstant().toEpochMilli(), noticedBefore)
                StateComplicationService.requestUpdate(ctx)
            }
            DrainWorker.enqueue(ctx)
        }
        finish()
    }

    companion object { const val EXTRA_FLAG_REF = "flag_ref" }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 10.sp, color = HEADER_COLOR, modifier = Modifier.weight(1f))
            Text(if (value > 0f) "${value.toInt()}" else "–", fontSize = 10.sp, color = ACCENT)
        }
        InlineSlider(
            value = value, onValueChange = onChange,
            valueRange = 0f..5f, steps = 4,
            decreaseIcon = { Icon(InlineSliderDefaults.Decrease, "less") },
            increaseIcon = { Icon(InlineSliderDefaults.Increase, "more") },
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
    }
}
