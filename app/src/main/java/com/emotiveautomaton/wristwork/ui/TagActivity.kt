package com.emotiveautomaton.wristwork.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Icon
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private val HEADER_COLOR = Color(0xFF9EB8D8)
private val ACCENT = Color(0xFFFFB36B)
private val PRIMARY_BG = Color(0xFF3B5A8C)
private val CELL_BG = Color(0xFF2A2F3A)
private val LINE_COLOR = Color(0xFF4A5568)
private val DIM = Color(0xFF7A8494)

/** A label row is a tombstone when its primary reads this: removal is an append, like everything. */
private const val DELETED = "DELETED"

/** One timeline marker: a labeled event (latest revision) or an unlabeled body-response flag. */
private data class TimelineItem(
    val time: OffsetDateTime,
    val event: TagEvent?,
    val flagId: Long?,
)

/**
 * The state editor.
 *
 * Interaction (owner, 2026-08-24, extended 2026-08-28). Nothing commits on tap: LONG-PRESS a
 * state sets the primary, TAP toggles a secondary, BACK saves whatever is dirty. Selecting a
 * timeline event saves the current edit and loads that one, so the whole six-hour window stays
 * editable.
 *
 * The sliders are gone (owner, 2026-08-28) and their meaning moved into the grid itself — less to
 * operate, and the owner's argument is that a forced 1-to-5 invents precision that was never felt:
 *   * low intensity  = NEUTRAL as the primary, with secondaries carrying the flavour
 *   * low confidence = secondaries only, no primary at all — a label that declines to say what it
 *     mainly was. That SAVES. It is data, not an unfinished form.
 * An event emptied completely — no primary, no secondaries — is REMOVED by appending a tombstone.
 * That is how the owner deletes something they placed themselves. Flags and prompts placed by the
 * system are not labels and cannot be removed at all.
 */
class TagActivity : ComponentActivity() {

    // Editor state lives on the activity so the back-press save can reach it.
    private var editingBase by mutableStateOf<TagEvent?>(null)   // row being revised, null = draft
    private var editingFlagId by mutableStateOf<Long?>(null)
    private var draftTsEvent by mutableStateOf<String?>(null)    // set for placed/flag/prompt drafts
    private var primary by mutableStateOf<String?>(null)
    private var secondaries by mutableStateOf(setOf<String>())
    private var noticedBefore by mutableStateOf(false)
    private var dirty by mutableStateOf(false)
    private var pendingNote by mutableStateOf<String?>(null)
    private var items by mutableStateOf<List<TimelineItem>>(emptyList())

    /** Non-null while the magnifier is open: the moment the cursor sits on. */
    private var scrubAt by mutableStateOf<OffsetDateTime?>(null)

    private var promptId: String? = null
    private var promptTs: String? = null
    private var promptSource: String? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (heard != null) {
                pendingNote = heard
                dirty = true
                // Kept twice on purpose. Attached to the label it belongs to, AND filed on its
                // own in the record with a timestamp — because context is the thing physiology
                // cannot supply, and a note that only exists inside a label is invisible to
                // anything reading the stream on its own terms.
                com.emotiveautomaton.wristwork.data.SpokenNote.file(applicationContext, heard, "grid")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getLongExtra(EXTRA_FLAG_REF, -1L).takeIf { it >= 0 }?.let { editingFlagId = it }
        // Opened from a prompt: the label is about the moment the prompt names, not the moment the
        // notification was finally tapped.
        promptId = intent.getStringExtra(EXTRA_PROMPT_ID)
        promptTs = intent.getStringExtra(EXTRA_PROMPT_TS)
        promptSource = intent.getStringExtra(EXTRA_PROMPT_SOURCE)
        if (promptTs != null) draftTsEvent = promptTs
        // ...and if the grid was opened any other way while a question is waiting, adopt it from
        // the recorded state. See adoptPendingPrompt().

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (scrubAt != null) { scrubAt = null; return }   // back closes the magnifier first
                saveIfDirty()
                finish()
            }
        })

        setContent {
            WristTheme {
                LaunchedEffect(Unit) { adoptPendingPrompt(); reloadTimeline() }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The magnifier needs room, so the whole screen rides up when it opens.
                    Spacer(Modifier.height(if (scrubAt != null) 18.dp else 44.dp))

                    Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                        val at = scrubAt
                        if (at == null) {
                            Timeline(
                                items, selectedItem(),
                                onSelect = { tapped -> selectItem(tapped) },
                                onScrub = { t -> scrubAt = snap15(t) },
                            )
                        } else {
                            Magnifier(center = at, items = items,
                                onMove = { minutes -> scrubAt = snap15(at.plusMinutes(minutes)) })
                        }
                    }

                    val at = scrubAt
                    if (at != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("<", fontSize = 18.sp, color = ACCENT, modifier = Modifier
                                .combinedClickable(onClick = { scrubAt = snap15(at.minusMinutes(15)) })
                                .padding(6.dp))
                            Text(at.format(DateTimeFormatter.ofPattern("H:mm")),
                                fontSize = 15.sp, color = Color.White)
                            Text(">", fontSize = 18.sp, color = ACCENT, modifier = Modifier
                                .combinedClickable(onClick = { scrubAt = snap15(at.plusMinutes(15)) })
                                .padding(6.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { placeAt(at) },
                                modifier = Modifier.size(width = 92.dp, height = 32.dp)) {
                                Text("place", fontSize = 11.sp)
                            }
                            Button(onClick = { scrubAt = null },
                                modifier = Modifier.size(width = 68.dp, height = 32.dp)) {
                                Text("back", fontSize = 11.sp)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                            Text("6h", fontSize = 9.sp, color = HEADER_COLOR,
                                modifier = Modifier.weight(1f))
                            Text("now", fontSize = 9.sp, color = HEADER_COLOR)
                        }
                    }

                    // context line: what is being edited
                    val ctxLine = when {
                        editingBase != null -> "editing " + fmtT(editingBase!!.tsEvent)
                        editingFlagId != null -> "labeling flagged moment"
                        promptId != null && draftTsEvent != null -> "asked at " + fmtT(draftTsEvent!!)
                        draftTsEvent != null -> "placed at " + fmtT(draftTsEvent!!)
                        else -> null
                    }
                    ctxLine?.let { Text(it, fontSize = 10.sp, color = ACCENT) }

                    // ---- grid 3/3/3: long-press = primary, tap = secondary, clear empties ----
                    (StateNames.CANONICAL + listOf("__CLEAR__")).chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { canon -> GridCell(canon) }
                        }
                    }

                    // ---- auxiliary: noticed toggle + mic note ----
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToggleChip(
                            checked = noticedBefore,
                            onCheckedChange = { noticedBefore = it; dirty = true },
                            label = { Text("noticed?", fontSize = 10.sp) },
                            toggleControl = { Icon(ToggleChipDefaults.switchIcon(checked = noticedBefore), null) },
                            modifier = Modifier.size(width = 128.dp, height = 30.dp),
                        )
                        Button(
                            onClick = {
                                speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                            },
                            modifier = Modifier.size(width = 84.dp, height = 30.dp),
                        ) { Text("mic note", fontSize = 10.sp) }
                    }
                    if (pendingNote != null) Text("note attached", fontSize = 9.sp, color = ACCENT)

                    // A real ECG, on purpose (owner 2026-08-28). The watch's own ECG app records
                    // thirty seconds of waveform at 250 Hz — the only true beat-to-beat data this
                    // hardware will give us — and taking it WHILE labelling is what makes it worth
                    // having, because then the truth and the label describe the same moment.
                    // Never automatic: that app opens by accident often enough already, and junk
                    // readings would poison the calibration.
                    Button(
                        onClick = { startEcg() },
                        modifier = Modifier.size(width = 130.dp, height = 30.dp),
                    ) { Text("ecg \u00b7 hold still 30s", fontSize = 9.sp) }

                    // ---- the rules, in small type, until they are second nature (owner) ----
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "long-press = primary · tap = secondary · back saves\n" +
                            "low intensity: Neutral primary + secondaries\n" +
                            "low confidence: secondaries only, no primary\n" +
                            "ecg: a real 30 s reading, saves first\n" +
                            "timeline: tap jumps to an event, long-press places one\n" +
                            "yours clears away when emptied · detected ones stay",
                        fontSize = 8.sp, color = DIM, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(44.dp))
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GridCell(canon: String) {
        if (canon == "__CLEAR__") {
            Box(
                Modifier.size(width = 62.dp, height = 38.dp)
                    .background(CELL_BG, RoundedCornerShape(19.dp))
                    .combinedClickable(onClick = { clearSelection() }),
                contentAlignment = Alignment.Center,
            ) { Text("clear", fontSize = 11.sp, color = HEADER_COLOR) }
            return
        }
        val isPrimary = primary == canon
        val isSecondary = canon in secondaries
        Box(
            Modifier.size(width = 62.dp, height = 38.dp)
                .background(if (isPrimary) PRIMARY_BG else CELL_BG, RoundedCornerShape(19.dp))
                .combinedClickable(
                    onClick = {
                        // Tapping the primary demotes it — the way back out of a primary without
                        // clearing the whole edit.
                        if (isPrimary) { primary = null; dirty = true; return@combinedClickable }
                        secondaries = if (isSecondary) secondaries - canon else secondaries + canon
                        dirty = true
                    },
                    onLongClick = {
                        primary = canon
                        secondaries = secondaries - canon
                        dirty = true
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(StateNames.humane(canon), fontSize = 12.sp,
                    color = if (isSecondary) ACCENT else Color.White)
                if (StateNames.renamed(canon))
                    Text(canon.lowercase(), fontSize = 7.sp, color = HEADER_COLOR)
            }
        }
    }

    // ---------- editor mechanics ----------

    /** Quarter-hour resolution, clamped to the six hours the timeline shows. */
    private fun snap15(t: OffsetDateTime): OffsetDateTime {
        val now = OffsetDateTime.now().withSecond(0).withNano(0)
        val snapped = t.withMinute((t.minute / 15) * 15).withSecond(0).withNano(0)
        val floor = now.minusHours(6)
        return when {
            snapped.isAfter(now) -> now
            snapped.isBefore(floor) -> floor
            else -> snapped
        }
    }

    /**
     * Hand off to the watch's ECG app, saving first so nothing in the editor is lost while we are
     * away, and dropping a marker in the health stream so the reading can be matched to this
     * moment when the waveform is pulled from the health API later.
     */
    private fun startEcg() {
        saveIfDirty()
        val ctx = applicationContext
        runCatching {
            runBlocking(Dispatchers.IO) {
                TagDb.get(ctx).raw().insert(
                    com.emotiveautomaton.wristwork.data.RawBatch(
                        ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        payload = "{\"kind\":\"ecg_requested\",\"t\":" +
                            (System.currentTimeMillis() / 1000) + "}",
                    )
                )
            }
            DrainWorker.enqueue(ctx)
        }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(
                        "com.fitbit.ecg",
                        "com.google.android.wearable.fitbit.ecg.ui.EcgComposeActivity",
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { android.util.Log.e("wristwork", "ECG app would not open", it) }
    }

    /** The magnifier's "place": start a label about that moment. */
    private fun placeAt(t: OffsetDateTime) {
        saveIfDirty()
        resetEditor()
        draftTsEvent = t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        scrubAt = null
    }

    private fun selectedItem(): TimelineItem? = items.firstOrNull {
        (it.event != null && it.event.id == editingBase?.id) ||
            (it.flagId != null && it.flagId == editingFlagId)
    }

    private fun selectItem(tapped: TimelineItem?) {
        saveIfDirty()
        resetEditor()
        when {
            tapped == null -> {}
            tapped.event != null -> {
                val e = tapped.event
                editingBase = e
                primary = e.primaryState.takeIf { it.isNotBlank() && it != DELETED }
                secondaries = e.secondaries.split(',').filter { it.isNotBlank() }.toSet()
                noticedBefore = e.noticedBefore ?: false
            }
            tapped.flagId != null -> {
                editingFlagId = tapped.flagId
                draftTsEvent = tapped.time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
            else -> {
                // The asked-about marker: keep pointing at that moment.
                draftTsEvent = tapped.time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
        }
    }

    /** CLEAR empties the selection. On an existing event that becomes a removal, once you go back. */
    private fun clearSelection() {
        primary = null
        secondaries = setOf()
        pendingNote = null
        noticedBefore = false
        dirty = editingBase != null    // emptying a draft is an undo; emptying a saved row deletes it
    }

    private fun resetEditor() {
        editingBase = null; editingFlagId = null; draftTsEvent = null
        primary = null; secondaries = setOf()
        noticedBefore = false; dirty = false; pendingNote = null
    }

    /**
     * Saving APPENDS, always. Three outcomes:
     *   * anything selected      -> a new row (a revision, when an existing event was loaded)
     *   * existing event emptied -> a tombstone row; it leaves the timeline, not the archive
     *   * empty draft            -> nothing at all, which is the undo
     */
    private fun saveIfDirty() {
        if (!dirty) return
        val base = editingBase
        val hasContent = primary != null || secondaries.isNotEmpty()
        if (!hasContent && base == null) return            // empty draft: discard
        val now = OffsetDateTime.now()
        val tsEvent = base?.tsEvent ?: draftTsEvent
            ?: now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        // Canonical source vocabulary (detector design §1). `random` is the permanent evaluation
        // stream and must never be assigned by anything but a random prompt.
        val ps = promptSource
        val source = when {
            base != null -> base.source          // a revision keeps the provenance it was born with
            ps != null -> ps                     // random | signal
            editingFlagId != null -> "google"
            else -> "self"
        }
        val ctx = applicationContext
        // The face shows the most recent thing known, so a label becomes the face whenever the
        // moment it describes is at least as recent as what the face already shows. The old test
        // was "was this a here-and-now label", which excluded every answer to a prompt — so
        // answering left the face showing a state the person had just replaced. A retro label
        // placed further back on the timeline still, correctly, does not move it.
        val eventMs = runCatching { OffsetDateTime.parse(tsEvent).toInstant().toEpochMilli() }
            .getOrDefault(now.toInstant().toEpochMilli())
        runBlocking(Dispatchers.IO) {
            TagDb.get(ctx).tags().insert(TagEvent(
                eventId = base?.eventId ?: UUID.randomUUID().toString(),
                tsEvent = tsEvent,
                tsEntered = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                // An empty primary is a real answer: "I cannot name the main one."
                primaryState = if (hasContent) (primary ?: "") else DELETED,
                secondaries = secondaries.joinToString(","),
                intensity = null, confidence = null,        // sliders retired 2026-08-28
                noticedBefore = noticedBefore, note = pendingNote, source = source,
                flagRef = editingFlagId?.toString(),
                promptId = base?.promptId ?: promptId, promptTs = base?.promptTs ?: promptTs,
                revises = base?.id,
            ))
            // Any submission answers a waiting prompt: the face goes back to showing an age, and
            // the question is retired so the next grid does not adopt it a second time.
            CurrentState.clearPrompt(ctx)
            if (hasContent && eventMs >= (CurrentState.read(ctx).sinceEpochMs ?: 0L)) {
                CurrentState.write(ctx, primary ?: "OTHER", eventMs, noticedBefore)
            }
        }
        StateComplicationService.requestUpdate(ctx)
        DrainWorker.enqueue(ctx)
        dirty = false
    }

    /**
     * Take up a waiting question however the grid was opened.
     *
     * The face says NEW; tapping the face used to open a grid that knew nothing about the
     * question — no marker on the line, and worse, an answer recorded as self-initiated rather
     * than as the answer to a randomly-timed question, which quietly removes it from the only
     * data that can ever measure whether the detector beats chance. Owner, 2026-09-01: the
     * notification is an imperfect way of creating those events.
     *
     * The notification's own extras still win when it was the door used, because they are the
     * same values and arrive earlier.
     */
    private suspend fun adoptPendingPrompt() {
        if (promptId != null || editingFlagId != null) return
        val held = withContext(Dispatchers.IO) { CurrentState.read(applicationContext) }
        if (!held.promptPending) return
        promptId = held.promptId
        promptTs = held.promptTs
        promptSource = held.promptSource
        // Only move the draft moment if the person has not already placed one themselves.
        if (draftTsEvent == null) draftTsEvent = held.promptTs
    }

    private suspend fun reloadTimeline() {
        withContext(Dispatchers.IO) {
            val db = TagDb.get(applicationContext)
            val events = db.tags().latestEvents(12).map {
                TimelineItem(OffsetDateTime.parse(it.tsEvent), it, null)
            }
            val flags = db.flags().latestBodyResponses(4).map {
                TimelineItem(OffsetDateTime.parse(it.ts), null, it.id)
            }
            val cutoff = OffsetDateTime.now().minusHours(6)
            // The moment being asked about gets its own marker, so opening the grid from a
            // prompt always shows WHERE on the line the question points (owner 2026-08-28:
            // "there should pretty much always be a visible triangle near the current time").
            // Without it the line looks empty until the first label lands on it.
            val asked = (draftTsEvent ?: promptTs)
                ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                ?.takeIf { t -> events.none { it.time == t } }
                ?.let { listOf(TimelineItem(it, null, null)) }
                ?: emptyList()
            items = (events + flags + asked).filter { it.time.isAfter(cutoff) }.sortedBy { it.time }
        }
    }

    private fun fmtT(iso: String) = runCatching {
        OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("H:mm"))
    }.getOrDefault("?")

    companion object {
        const val EXTRA_FLAG_REF = "flag_ref"
        const val EXTRA_PROMPT_ID = "prompt_id"
        const val EXTRA_PROMPT_TS = "prompt_ts"
        const val EXTRA_PROMPT_SOURCE = "prompt_source"
    }
}

/**
 * Horizontal six-hour line; each event is a small arrow above it. A tap jumps to the nearest
 * event; a long-press opens the magnifier at the moment pressed.
 */
@Composable
private fun Timeline(
    items: List<TimelineItem>,
    selected: TimelineItem?,
    onSelect: (TimelineItem?) -> Unit,
    onScrub: (OffsetDateTime) -> Unit,
) {
    val now = OffsetDateTime.now()
    val windowS = 6f * 3600f
    Canvas(
        Modifier.fillMaxWidth().height(40.dp)
            .pointerInput(items) {
                detectTapGestures(
                    onLongPress = { p ->
                        val frac = (p.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onScrub(now.minusSeconds(((1f - frac) * windowS).toLong()))
                    },
                    onTap = { tap ->
                        val w = size.width.toFloat()
                        val nearest = items.minByOrNull {
                            val x = w * (1f - (java.time.Duration.between(it.time, now).seconds / windowS))
                            kotlin.math.abs(x - tap.x)
                        }
                        val hit = nearest?.let {
                            val x = w * (1f - (java.time.Duration.between(it.time, now).seconds / windowS))
                            kotlin.math.abs(x - tap.x) < 26.dp.toPx()
                        } ?: false
                        onSelect(if (hit) nearest else null)
                    },
                )
            },
    ) {
        val midY = size.height * 0.62f
        drawLine(LINE_COLOR, Offset(0f, midY), Offset(size.width, midY), 2.dp.toPx())
        drawLine(HEADER_COLOR, Offset(size.width - 1.5f, midY - 6.dp.toPx()),
            Offset(size.width - 1.5f, midY + 6.dp.toPx()), 2.dp.toPx())
        items.forEach { item ->
            val ageS = java.time.Duration.between(item.time, now).seconds.toFloat()
            if (ageS > windowS) return@forEach
            val x = (size.width * (1f - ageS / windowS)).coerceIn(4f, size.width - 4f)
            val isSel = item == selected
            val color = when {
                isSel -> ACCENT
                item.flagId != null -> Color(0xFFFF8080)
                item.event == null -> ACCENT      // the moment a prompt is asking about
                else -> Color.White
            }
            val h = if (isSel) 11.dp.toPx() else 8.dp.toPx()
            val wHalf = if (isSel) 5.5f.dp.toPx() else 4f.dp.toPx()
            val path = Path().apply {
                moveTo(x, midY - 2.dp.toPx())
                lineTo(x - wHalf, midY - 2.dp.toPx() - h)
                lineTo(x + wHalf, midY - 2.dp.toPx() - h)
                close()
            }
            drawPath(path, color)
        }
    }
}

/**
 * The magnifier: a ninety-minute window around the cursor, ticked every quarter hour, so a
 * particular moment can actually be picked on a watch-sized screen. Drag to pan; the arrows below
 * step fifteen minutes. Six hours back is the floor, now is the ceiling.
 */
@Composable
private fun Magnifier(
    center: OffsetDateTime,
    items: List<TimelineItem>,
    onMove: (Long) -> Unit,
) {
    val spanMin = 90f
    Canvas(
        Modifier.fillMaxWidth().height(84.dp)
            .pointerInput(center) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val minutes = (-drag.x / size.width.toFloat() * spanMin).toLong()
                    if (minutes != 0L) onMove(minutes)
                }
            },
    ) {
        val midY = size.height * 0.60f
        drawLine(LINE_COLOR, Offset(0f, midY), Offset(size.width, midY), 2.dp.toPx())
        fun xOf(t: OffsetDateTime): Float {
            val dMin = java.time.Duration.between(center, t).toMinutes().toFloat()
            return size.width / 2f + (dMin / spanMin) * size.width
        }
        var tick = center.minusMinutes(45).withSecond(0).withNano(0)
        tick = tick.withMinute((tick.minute / 15) * 15)
        repeat(9) {
            val x = xOf(tick)
            if (x >= 0f && x <= size.width) {
                val onTheHour = tick.minute == 0
                drawLine(
                    if (onTheHour) HEADER_COLOR else LINE_COLOR,
                    Offset(x, midY),
                    Offset(x, midY + (if (onTheHour) 9.dp.toPx() else 5.dp.toPx())),
                    1.5f.dp.toPx(),
                )
            }
            tick = tick.plusMinutes(15)
        }
        items.forEach { item ->
            val x = xOf(item.time)
            if (x < 0f || x > size.width) return@forEach
            val color = if (item.flagId != null) Color(0xFFFF8080) else Color.White
            val path = Path().apply {
                moveTo(x, midY - 3.dp.toPx())
                lineTo(x - 4.dp.toPx(), midY - 12.dp.toPx())
                lineTo(x + 4.dp.toPx(), midY - 12.dp.toPx())
                close()
            }
            drawPath(path, color)
        }
        val cx = size.width / 2f
        drawLine(ACCENT, Offset(cx, midY - 20.dp.toPx()), Offset(cx, midY + 12.dp.toPx()), 2.dp.toPx())
    }
}
