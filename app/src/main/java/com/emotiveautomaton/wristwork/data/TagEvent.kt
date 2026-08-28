package com.emotiveautomaton.wristwork.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One label row, schema v2 (HEALTH_DESIGN.md). Append-only: relabeling an event inserts a NEW
 * row with the same [eventId] and [revises] pointing at the superseded row — nothing is ever
 * edited in place (immutability law + owner-approved amendment). An event's current state is its
 * newest row. `uploaded` is transport bookkeeping, not data.
 */
@Entity(tableName = "tag_events")
data class TagEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,           // stable per event across revisions (uuid)
    val tsEvent: String,           // when the state was/is — what the label is ABOUT (ISO-8601)
    val tsEntered: String,         // when the human entered it; latency = entered − event
    val primaryState: String,      // canonical Panksepp name, one of the eight
    val secondaries: String,       // comma-joined canonical names; "" = none (mixtures first-class)
    val intensity: Int?,           // 1..5, optional
    val confidence: Int?,          // 1..5, optional — high confidence of a weak feeling is a thing
    val noticedBefore: Boolean?,   // had you caught this state yourself before the cue/tag moment
    val note: String?,             // mic transcript, optional
    // The holdout rule lives or dies on this field (detector design §5): `random`-sourced labels
    // are EVALUATION ONLY, never training, forever. Canonical vocabulary from 2026-08-26:
    //   random | signal | self | google
    // Legacy values still in the archive map as: manual -> self, timeline-retro -> self,
    // fitbit-flag -> google, model-alert -> signal. History is not rewritten; the mapping is
    // applied at analysis time (see README).
    val source: String,
    val flagRef: String?,          // flag event that cued this, if any
    val promptId: String? = null,  // the prompt that asked, if this label answers one
    val promptTs: String? = null,  // the moment the prompt was ABOUT (ISO-8601)
    val revises: Long?,            // row id this revision supersedes, if any
    val uploaded: Boolean = false,
)

/** One captured Fitbit notification (family-1 capture; also the canary's raw material). */
@Entity(tableName = "flag_events")
data class FlagEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,                // ISO-8601, when the notification posted
    val pkg: String,
    val title: String?,
    val text: String?,
    val kind: String,              // fitbit-notif | body-response
    val uploaded: Boolean = false,
)

/** One batch of raw physiology samples, serialized JSON (family-2 owned collection). */
@Entity(tableName = "raw_batches")
data class RawBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,                // ISO-8601, batch delivery time
    // JSON, one of:
    //   {kind:"hr"|"skin_temp", samples:[[epoch_s,value],...]}
    //   {kind:"inventory", ...}   what the device could sense, once per app version
    //   {kind:"rr", source:"<strap>", samples:[[epoch_ms,rr_ms],...]}   RESERVED for a future
    //     BLE chest strap: true beat-to-beat intervals, the only route to real RMSSD on this
    //     setup, intended as an occasional calibrator for our bpm-derived variability rather
    //     than a daily wearable. Nothing writes this kind yet; the archive accepts it today so
    //     that the day a strap appears, no schema changes and no migration are needed.
    val payload: String,
    val uploaded: Boolean = false,
)
