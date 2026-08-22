package com.emotiveautomaton.wristwork.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TagEvent::class], version = 1, exportSchema = false)
abstract class TagDb : RoomDatabase() {
    abstract fun tags(): TagDao

    companion object {
        @Volatile private var instance: TagDb? = null
        fun get(context: Context): TagDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TagDb::class.java, "tags.db")
                .build().also { instance = it }
        }
    }
}
