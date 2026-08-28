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

    data class Snapshot(
        val state: String?, val sinceEpochMs: Long?, val noticed: Boolean,
        /** A prompt is waiting to be answered: the face says NEW until a label is submitted. */
        val promptPending: Boolean = false,
    )

    suspend fun read(context: Context): Snapshot {
        val p = context.store.data.first()
        return Snapshot(p[KEY_STATE], p[KEY_SINCE], p[KEY_NOTICED] ?: false, p[KEY_PENDING] ?: false)
    }

    /** Set when a prompt fires, cleared the moment anything is submitted. */
    suspend fun setPromptPending(context: Context, pending: Boolean) {
        context.store.edit { it[KEY_PENDING] = pending }
    }

    suspend fun write(context: Context, state: String, sinceEpochMs: Long, noticed: Boolean) {
        context.store.edit {
            it[KEY_STATE] = state; it[KEY_SINCE] = sinceEpochMs; it[KEY_NOTICED] = noticed
        }
    }
}
