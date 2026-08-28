package com.emotiveautomaton.wristwork.data

import android.content.Context
import com.emotiveautomaton.wristwork.work.DrainWorker
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Anything said out loud, kept.
 *
 * Physiology says that something changed; it never says what was happening. Context is the piece
 * that makes a labelled moment interpretable a year later, and on a wrist the only practical way
 * to capture it is to say it. So every transcript the watch produces — beside a label, or on its
 * own from the rig frame — is filed into the same append-only record as the sensor streams, with
 * a timestamp and where it came from.
 *
 * NO AUDIO IS RECORDED OR STORED, here or anywhere in this app. The platform's recogniser hears
 * the speech and returns words; we keep the words. That is both the owner's instruction
 * ("transcribing it and tossing it immediately") and the only version of this worth having on a
 * device whose battery and privacy budget are both spoken for.
 */
object SpokenNote {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun file(context: Context, text: String, from: String) {
        val ctx = context.applicationContext
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            runCatching {
                TagDb.get(ctx).raw().insert(RawBatch(
                    ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    payload = JsonObject(mapOf(
                        "kind" to JsonPrimitive("note"),
                        "t" to JsonPrimitive(Instant.now().epochSecond),
                        "from" to JsonPrimitive(from),
                        "text" to JsonPrimitive(trimmed),
                    )).toString(),
                ))
                DrainWorker.enqueue(ctx)
            }
        }
    }
}
