package com.emotiveautomaton.wristwork.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One raw label. Immutable once written (the project's first law); `uploaded` is the only mutable
 * column and is bookkeeping, not data — the server-side labels.jsonl is the archive of record.
 */
@Entity(tableName = "tag_events")
data class TagEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,            // ISO-8601 with offset, stamped at tap time
    val state: String,         // SEEK|RAGE|FEAR|LUST|CARE|GRIEF|PLAY|OTHER
    val noticed: Boolean,      // "already noticed?" — the experiment's dependent variable
    val note: String?,         // optional voice transcript
    val source: String = "manual",
    val uploaded: Boolean = false,
)
