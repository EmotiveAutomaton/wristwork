package com.emotiveautomaton.wristwork.net

import com.emotiveautomaton.wristwork.BuildConfig
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The wrist half of speaking a print into existence.
 *
 * The other half lives in the sibling project Fetch, which listens for a spoken sentence, reduces
 * it to search terms with a local model, searches the model catalogue, filters by licence, and
 * offers up to three candidates with the listing's own photograph. Only the one that gets picked
 * is downloaded and sliced, so the real weight and print time arrive at the moment of committing
 * rather than three times over for two answers nobody wanted.
 *
 * THE DIVISION OF LABOUR IS DELIBERATE and is the owner's ruling (2026-08-28): **Fetch renders no
 * chooser.** It publishes candidates and consumes answers; the choosing happens here, on the
 * wrist, because that is where the person is when they think of the thing they want. Everything
 * in this file is written against the shapes Fetch already publishes and parses — read from its
 * source, not agreed in the abstract, so the two halves cannot drift on a guess.
 *
 * Wrist speaks, on the request topic:
 *   {"text": "a small hook to hang keys by the door", "spoken_at": "2026-08-31T17:40:00-07:00"}
 *   {"action": "choose",  "request_id": "...", "model_id": "..."}
 *   {"action": "confirm", "request_id": "..."}
 *   {"action": "decline", "request_id": "..."}
 *
 * Fetch answers, on the proposal topic — three shapes, told apart by their contents:
 *   a shortlist      {request_id, heard, expires_at, candidates:[{n, model_id, title, image_url…}]}
 *   a confirmation   {request_id, model_id, title, grams, print_time, minutes, bed_clear, bed_note…}
 *   a plain sentence (not JSON) — something went wrong, or there was nothing to offer
 *
 * NOTHING HERE STARTS A PRINT ON ITS OWN. Two separate human answers stand between a sentence and
 * a moving nozzle, and Fetch additionally refuses to start onto a bed it cannot prove is empty.
 */
object PrintLoop {

    /** What Fetch is asking the wrist right now, decoded. */
    sealed interface Proposal {
        val requestId: String
        val expiresAt: String?

        /** When it was published. The chooser waits for something NEWER than what it answered. */
        val at: Long

        data class Shortlist(
            override val requestId: String, override val expiresAt: String?, override val at: Long,
            val heard: String, val candidates: List<Candidate>,
        ) : Proposal

        data class Confirm(
            override val requestId: String, override val expiresAt: String?, override val at: Long,
            val modelId: String, val title: String, val grams: Double?, val printTime: String?,
            val imageUrl: String?, val bedClear: Boolean?, val bedNote: String,
        ) : Proposal

        /** Fetch said something in plain words: no candidates, a failed search, a stale answer. */
        data class Said(
            override val requestId: String, override val expiresAt: String?, override val at: Long,
            val title: String, val text: String,
        ) : Proposal
    }

    data class Candidate(
        val n: Int, val modelId: String, val title: String,
        val imageUrl: String?, val licence: String?,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- speaking ----------------------------------------------------------------------------

    /** A spoken sentence, on its way to Fetch. Returns false if the bus refused it. */
    fun speak(text: String): Boolean {
        val body = JsonObject(
            mapOf(
                "text" to JsonPrimitive(text.trim()),
                // Fetch records when it was SAID, not when it arrived, because a request spoken
                // out of signal and delivered twenty minutes later is still about that moment.
                "spoken_at" to JsonPrimitive(
                    OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
            )
        ).toString()
        return post(BuildConfig.TOPIC_REQUESTS, body)
    }

    /** An answer to a question Fetch asked. `modelId` only matters for a choice. */
    fun answer(action: String, requestId: String, modelId: String? = null): Boolean {
        val body = JsonObject(buildMap {
            put("action", JsonPrimitive(action))
            put("request_id", JsonPrimitive(requestId))
            modelId?.let { put("model_id", JsonPrimitive(it)) }
        }).toString()
        return post(BuildConfig.TOPIC_REQUESTS, body)
    }

    private fun post(topic: String, body: String): Boolean = runCatching {
        NtfyClient.http.newCall(
            Request.Builder().url("${NtfyClient.baseUrl}/$topic")
                // Silent, like everything else the watch publishes: the person holding the watch
                // is the one who just spoke, and does not need to be told they did.
                .header("Priority", "min")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use {
            if (!it.isSuccessful) android.util.Log.e(
                "wristwork-print", "post $topic failed http=${it.code}")
            it.isSuccessful
        }
    }.onFailure { android.util.Log.e("wristwork-print", "post $topic threw", it) }
        .getOrDefault(false)

    // ---- listening ---------------------------------------------------------------------------

    /**
     * The newest thing Fetch has said, or null if it has said nothing recently.
     *
     * A six-hour window: proposals expire in half an hour on Fetch's side, and anything older is
     * of no use to a person standing in front of a printer. Answered requests are filtered out by
     * the caller, which knows what it has already replied to.
     */
    fun latest(): Proposal? = runCatching {
        val url = "${NtfyClient.baseUrl}/${BuildConfig.TOPIC_PROPOSALS}/json?poll=1&since=6h"
        val body = NtfyClient.http.newCall(Request.Builder().url(url).build()).execute()
            .use { if (it.isSuccessful) it.body.string() else "" }
        var newest: Proposal? = null
        for (line in body.lines().filter { it.isNotBlank() }) {
            val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["event"]?.jsonPrimitive?.content != "message") continue
            decode(o)?.let { newest = it }        // the poll arrives oldest-first
        }
        newest
    }.getOrNull()

    private fun decode(envelope: JsonObject): Proposal? {
        val title = envelope["title"]?.jsonPrimitive?.content ?: ""
        val at = envelope["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val msg = envelope["message"]?.jsonPrimitive?.content?.trim() ?: return null
        if (!msg.startsWith("{")) {
            // Not every answer is a question. Fetch says plain sentences when a search fails or a
            // shortlist goes stale, and swallowing those would leave the wrist waiting forever.
            return Proposal.Said(requestId = "", expiresAt = null, at = at,
                                 title = title, text = msg)
        }
        val o = runCatching { json.parseToJsonElement(msg).jsonObject }.getOrNull() ?: return null
        val id = o["request_id"]?.jsonPrimitive?.content ?: return null
        val expires = o["expires_at"]?.jsonPrimitive?.content
        o["candidates"]?.let { arr ->
            val list = runCatching {
                arr.jsonArray.mapIndexed { i, e ->
                    val c = e.jsonObject
                    Candidate(
                        n = c["n"]?.jsonPrimitive?.content?.toIntOrNull() ?: (i + 1),
                        modelId = c["model_id"]?.jsonPrimitive?.content ?: return@mapIndexed null,
                        title = c["title"]?.jsonPrimitive?.content ?: "untitled",
                        imageUrl = c["image_url"]?.jsonPrimitive?.contentOrNull(),
                        licence = c["licence"]?.jsonPrimitive?.contentOrNull(),
                    )
                }.filterNotNull()
            }.getOrNull().orEmpty()
            if (list.isNotEmpty()) return Proposal.Shortlist(
                requestId = id, expiresAt = expires, at = at,
                heard = o["heard"]?.jsonPrimitive?.content ?: "", candidates = list)
        }
        val modelId = o["model_id"]?.jsonPrimitive?.content ?: return null
        return Proposal.Confirm(
            requestId = id, expiresAt = expires, at = at, modelId = modelId,
            title = o["title"]?.jsonPrimitive?.content ?: "untitled",
            grams = o["grams"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            printTime = o["print_time"]?.jsonPrimitive?.contentOrNull(),
            imageUrl = o["image_url"]?.jsonPrimitive?.contentOrNull(),
            bedClear = o["bed_clear"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            bedNote = o["bed_note"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        content.takeIf { it.isNotBlank() && it != "null" }

    /**
     * Any picture Fetch pointed at. It links to the listing's own photograph, never uploads it.
     *
     * The picture is not decoration: an agent-sliced job has no thumbnail of its own, so the
     * listing photograph is the ONLY thing on the confirmation screen that says what the object
     * actually is. A silent failure here degrades the frame to a blank card, which is the exact
     * regression the feasibility work warned about — so a miss is logged with its reason.
     */
    fun image(url: String): ByteArray? = runCatching {
        // The plain client: these are public listing URLs on someone else's server, and our bus
        // token has no business being sent to them. A real user agent, because a bare HTTP client
        // is refused outright by a good many image hosts (Cloudflare does it to us already).
        NtfyClient.plain.newCall(
            Request.Builder().url(url).header("User-Agent", "wristwork/1.0 (Wear OS)").build()
        ).execute().use {
            if (it.isSuccessful) it.body.bytes()
            else { android.util.Log.e("wristwork-print", "picture $url -> http ${it.code}"); null }
        }
    }.onFailure { android.util.Log.e("wristwork-print", "picture $url threw", it) }.getOrNull()
}
