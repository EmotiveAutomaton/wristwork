package com.emotiveautomaton.wristwork.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TagDao {
    @Insert suspend fun insert(event: TagEvent): Long
    @Query("SELECT * FROM tag_events WHERE uploaded = 0 ORDER BY id") suspend fun pending(): List<TagEvent>
    @Query("UPDATE tag_events SET uploaded = 1 WHERE id = :id") suspend fun markUploaded(id: Long)

    /** Newest revision per event, newest events first — the timeline's label stream. */
    @Query(
        """SELECT * FROM tag_events WHERE id IN
           (SELECT MAX(id) FROM tag_events GROUP BY eventId)
           ORDER BY tsEvent DESC LIMIT :n"""
    )
    suspend fun latestEvents(n: Int): List<TagEvent>

    @Query("SELECT * FROM tag_events WHERE eventId = :eventId ORDER BY id DESC LIMIT 1")
    suspend fun currentOf(eventId: String): TagEvent?
}

@Dao
interface FlagDao {
    @Insert suspend fun insert(flag: FlagEvent): Long
    @Query("SELECT * FROM flag_events WHERE uploaded = 0 ORDER BY id") suspend fun pending(): List<FlagEvent>
    @Query("UPDATE flag_events SET uploaded = 1 WHERE id = :id") suspend fun markUploaded(id: Long)
    @Query("SELECT * FROM flag_events WHERE kind = 'body-response' ORDER BY id DESC LIMIT :n")
    suspend fun latestBodyResponses(n: Int): List<FlagEvent>
}

@Dao
interface RawDao {
    @Insert suspend fun insert(batch: RawBatch): Long
    @Query("SELECT * FROM raw_batches WHERE uploaded = 0 ORDER BY id") suspend fun pending(): List<RawBatch>
    @Query("UPDATE raw_batches SET uploaded = 1 WHERE id = :id") suspend fun markUploaded(id: Long)
    @Query("DELETE FROM raw_batches WHERE uploaded = 1 AND id < :keepAfter") suspend fun prune(keepAfter: Long)
}
