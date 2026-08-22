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

    data class Snapshot(val state: String?, val sinceEpochMs: Long?, val noticed: Boolean)

    suspend fun read(context: Context): Snapshot {
        val p = context.store.data.first()
        return Snapshot(p[KEY_STATE], p[KEY_SINCE], p[KEY_NOTICED] ?: false)
    }

    suspend fun write(context: Context, state: String, sinceEpochMs: Long, noticed: Boolean) {
        context.store.edit {
            it[KEY_STATE] = state; it[KEY_SINCE] = sinceEpochMs; it[KEY_NOTICED] = noticed
        }
    }
}
