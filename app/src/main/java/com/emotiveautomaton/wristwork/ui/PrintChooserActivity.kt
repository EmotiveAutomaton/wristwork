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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import com.emotiveautomaton.wristwork.net.PrintLoop
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val HEADER_COLOR = Color(0xFF9EB8D8)
private val ACCENT = Color(0xFFFFB36B)
private val WARN_COLOR = Color(0xFFFF7A7A)

/**
 * Choosing what to print, on the wrist.
 *
 * Opened from the printer frame after speaking a request. The sibling project Fetch does the
 * searching and the slicing and deliberately renders no chooser of its own, so this screen is the
 * whole of the human half: two questions, in order.
 *
 *   1. **Which one?** Up to three models it is allowed to print, each with the listing's own
 *      photograph — a picture of the actual printed object, which says more about whether it is
 *      the right thing than a render of the toolpath would.
 *   2. **This one, really?** Only the chosen model is downloaded and sliced, so the weight and
 *      the print time on this screen are real numbers from the slicer rather than estimates.
 *
 * Either question can be answered "no", and doing nothing is also an answer: the proposal expires
 * on Fetch's side after half an hour and a stale yes can never start a print.
 *
 * THE BED WARNING IS NOT DECORATION. A finished job and a cleared plate look identical to the
 * printer — there is no bed-occupancy sensor and no way to ask — so a print started onto a plate
 * that still holds the last part drives the nozzle into it. Fetch refuses unless the printer was
 * returned to idle by a human; when this screen says the bed may not be clear, that refusal is
 * what is being reported, and clearing the plate is a thing only a person can do.
 */
class PrintChooserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WristTheme {
                var proposal by remember { mutableStateOf<PrintLoop.Proposal?>(null) }
                var picture by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                var status by remember { mutableStateOf("asking…") }
                // The publish time of whatever we last replied to. Until something NEWER arrives,
                // the old question is still on screen and must not be answerable twice.
                var answeredAt by remember { mutableStateOf(0L) }

                LaunchedEffect(Unit) {
                    var lastPicture: String? = null
                    while (true) {
                        withContext(Dispatchers.IO) {
                            val p = PrintLoop.latest()
                            if (p != null && p.at > answeredAt) {
                                proposal = p
                                status = ""
                                val url = when (p) {
                                    is PrintLoop.Proposal.Shortlist ->
                                        p.candidates.firstOrNull()?.imageUrl
                                    is PrintLoop.Proposal.Confirm -> p.imageUrl
                                    else -> null
                                }
                                if (url != null && url != lastPicture) {
                                    PrintLoop.image(url)?.let { b ->
                                        BitmapFactory.decodeByteArray(b, 0, b.size)?.let {
                                            picture = it; lastPicture = url
                                        }
                                    }
                                } else if (url == null) picture = null
                            } else if (proposal == null) {
                                status = "nothing waiting"
                            }
                        }
                        delay(4_000)
                    }
                }

                fun reply(action: String, modelId: String? = null) {
                    val p = proposal ?: return
                    answeredAt = p.at
                    proposal = null
                    picture = null
                    status = if (action == "decline") "told it no" else "sent — waiting…"
                    Thread {
                        val ok = PrintLoop.answer(action, p.requestId, modelId)
                        if (!ok) { status = "could not reach the bus"; answeredAt = 0L }
                    }.start()
                }

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(34.dp))
                    Text("print", fontSize = 13.sp, color = HEADER_COLOR)
                    if (status.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Text(status, fontSize = 12.sp)
                    }
                    picture?.let {
                        Spacer(Modifier.height(6.dp))
                        Image(
                            it.asImageBitmap(), contentDescription = "the printed object",
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    when (val p = proposal) {
                        is PrintLoop.Proposal.Shortlist -> {
                            Spacer(Modifier.height(6.dp))
                            if (p.heard.isNotBlank()) {
                                Text("“${p.heard}”", fontSize = 11.sp, color = HEADER_COLOR)
                            }
                            Expiry(p.expiresAt)
                            Spacer(Modifier.height(6.dp))
                            p.candidates.forEach { c ->
                                Button(
                                    onClick = { reply("choose", c.modelId) },
                                    modifier = Modifier.fillMaxWidth().height(44.dp)
                                        .padding(vertical = 3.dp),
                                ) {
                                    Text(c.title, fontSize = 11.sp, color = Color.White, maxLines = 2)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { reply("decline") },
                                modifier = Modifier.size(width = 120.dp, height = 32.dp),
                            ) { Text("none of these", fontSize = 11.sp) }
                        }

                        is PrintLoop.Proposal.Confirm -> {
                            Spacer(Modifier.height(4.dp))
                            Text(p.title, fontSize = 12.sp, color = Color.White, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                            val grams = p.grams?.let { "%.0f g".format(it) }
                            Text(listOfNotNull(grams, p.printTime).joinToString("  ·  "),
                                fontSize = 15.sp, color = ACCENT)
                            Expiry(p.expiresAt)
                            if (p.bedClear == false || p.bedNote.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(p.bedNote.ifBlank { "the bed may not be clear" },
                                    fontSize = 11.sp, color = WARN_COLOR)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Button(
                                    onClick = { reply("confirm") },
                                    modifier = Modifier.size(width = 74.dp, height = 34.dp),
                                ) { Text("print", fontSize = 12.sp) }
                                Spacer(Modifier.size(width = 8.dp, height = 1.dp))
                                Button(
                                    onClick = { reply("decline") },
                                    modifier = Modifier.size(width = 60.dp, height = 34.dp),
                                ) { Text("no", fontSize = 12.sp) }
                            }
                        }

                        is PrintLoop.Proposal.Said -> {
                            Spacer(Modifier.height(8.dp))
                            if (p.title.isNotBlank()) {
                                Text(p.title, fontSize = 12.sp, color = ACCENT)
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(p.text, fontSize = 11.sp, color = Color.White)
                        }

                        null -> Unit
                    }
                    Spacer(Modifier.height(60.dp))
                }
            }
        }
    }
}

/** How long the offer stands. Silence is a valid answer, so the deadline has to be visible. */
@Composable
private fun Expiry(expiresAt: String?) {
    val mins = expiresAt?.let {
        runCatching {
            (OffsetDateTime.parse(it).toInstant().epochSecond - Instant.now().epochSecond) / 60
        }.getOrNull()
    } ?: return
    Spacer(Modifier.height(3.dp))
    Text(
        if (mins <= 0) "this offer has expired" else "stands for $mins min",
        fontSize = 10.sp, color = if (mins <= 0) WARN_COLOR else HEADER_COLOR,
    )
}
