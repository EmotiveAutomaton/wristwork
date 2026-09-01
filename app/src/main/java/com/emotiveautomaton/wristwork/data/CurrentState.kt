package com.emotiveautomaton.wristwork.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** What the face shows: the latest state code and when it was tagged. Small scalars only (D3). */
object CurrentState {
    private val Context.store by preferencesDataStore(name = "current_state")
    private val KEY_STATE = stringPreferencesKey("state")
    private val KEY_SINCE = longPreferencesKey("since_epoch_ms")
    private val KEY_NOTICED = booleanPreferencesKey("noticed")
    private val KEY_PENDING = booleanPreferencesKey("prompt_pending")
    // The question itself, not just the fact of one. It used to live only inside the
    // notification's tap action, which made the notification the ONLY way to answer correctly:
    // opening the grid from the face lost the moment being asked about (so the timeline drew no
    // marker) and lost the question's identity (so a randomly-timed answer was filed as
    // self-initiated and silently dropped out of the evaluation set). Owner, 2026-09-01:
    // "the notifications are an imperfect way of creating those events."
    private val KEY_PROMPT_ID = stringPreferencesKey("prompt_id")
    private val KEY_PROMPT_TS = stringPreferencesKey("prompt_ts")
    private val KEY_PROMPT_SOURCE = stringPreferencesKey("prompt_source")

    data class Snapshot(
        val state: String?, val sinceEpochMs: Long?, val noticed: Boolean,
        /** A prompt is waiting to be answered: the face says NEW until a label is submitted. */
        val promptPending: Boolean = false,
        /** Which question is waiting, and about which moment. Null when none is. */
        val promptId: String? = null,
        val promptTs: String? = null,
        val promptSource: String? = null,
    )

    suspend fun read(context: Context): Snapshot {
        val p = context.store.data.first()
        return Snapshot(
            p[KEY_STATE], p[KEY_SINCE], p[KEY_NOTICED] ?: false, p[KEY_PENDING] ?: false,
            p[KEY_PROMPT_ID], p[KEY_PROMPT_TS], p[KEY_PROMPT_SOURCE],
        )
    }

    /** A question is waiting. Recorded in full, so any way into the grid can answer it. */
    suspend fun setPrompt(context: Context, id: String, source: String, tsIso: String) {
        context.store.edit {
            it[KEY_PENDING] = true
            it[KEY_PROMPT_ID] = id
            it[KEY_PROMPT_TS] = tsIso
            it[KEY_PROMPT_SOURCE] = source
        }
    }

    /** Cleared the moment anything is submitted, and when a question goes stale unanswered. */
    suspend fun clearPrompt(context: Context) {
        context.store.edit {
            it[KEY_PENDING] = false
            it.remove(KEY_PROMPT_ID); it.remove(KEY_PROMPT_TS); it.remove(KEY_PROMPT_SOURCE)
        }
    }

    suspend fun write(context: Context, state: String, sinceEpochMs: Long, noticed: Boolean) {
        context.store.edit {
            it[KEY_STATE] = state; it[KEY_SINCE] = sinceEpochMs; it[KEY_NOTICED] = noticed
        }
    }
}
