package com.emotiveautomaton.wristwork.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.emotiveautomaton.wristwork.complication.StateComplicationService
import com.emotiveautomaton.wristwork.data.CurrentState
import com.emotiveautomaton.wristwork.data.TagDb
import com.emotiveautomaton.wristwork.data.TagEvent
import com.emotiveautomaton.wristwork.work.DrainWorker
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Panksepp's seven primaries plus the open-world hatch. Order is the owner's; do not regroup. */
private val STATES = listOf("SEEK", "RAGE", "FEAR", "LUST", "CARE", "GRIEF", "PLAY", "OTHER")

/**
 * The whole UI of v1: one toggle, a 2x4 grid, an optional mic. Two taps, auto-close.
 * Persistence and upload run on an application-scope so finish() cannot cancel them.
 */
class TagActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingNote: String? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            pendingNote = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var noticed by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Spacer(Modifier.height(24.dp))
                    ToggleChip(
                        checked = noticed,
                        onCheckedChange = { noticed = it },
                        label = { Text("already noticed?", fontSize = 12.sp) },
                        toggleControl = { Icon(ToggleChipDefaults.switchIcon(checked = noticed), contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    )
                    STATES.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { s ->
                                Button(
                                    onClick = { commit(s, noticed) },
                                    modifier = Modifier.size(width = 74.dp, height = 40.dp),
                                    colors = ButtonDefaults.secondaryButtonColors(),
                                ) { Text(s, fontSize = 13.sp) }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            speech.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                            )
                        },
                        modifier = Modifier.size(width = 74.dp, height = 34.dp),
                    ) { Text("mic note", fontSize = 11.sp) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    private fun commit(state: String, noticed: Boolean) {
        val now = OffsetDateTime.now()
        val note = pendingNote
        val ctx = applicationContext
        appScope.launch {
            TagDb.get(ctx).tags().insert(
                TagEvent(
                    ts = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    state = state, noticed = noticed, note = note,
                )
            )
            CurrentState.write(ctx, state, now.toInstant().toEpochMilli(), noticed)
            StateComplicationService.requestUpdate(ctx)
            DrainWorker.enqueue(ctx)
        }
        finish()
    }
}
