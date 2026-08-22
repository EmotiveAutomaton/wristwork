package com.emotiveautomaton.wristwork.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TagDao {
    @Insert suspend fun insert(event: TagEvent): Long
    @Query("SELECT * FROM tag_events WHERE uploaded = 0 ORDER BY id") suspend fun pending(): List<TagEvent>
    @Query("UPDATE tag_events SET uploaded = 1 WHERE id = :id") suspend fun markUploaded(id: Long)
}
