package com.emotiveautomaton.wristwork.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TagEvent::class, FlagEvent::class, RawBatch::class], version = 2, exportSchema = false)
abstract class TagDb : RoomDatabase() {
    abstract fun tags(): TagDao
    abstract fun flags(): FlagDao
    abstract fun raw(): RawDao

    companion object {
        /** v1 rows (ts, state, noticed) become v2 rows: ts fills both timestamps, state becomes
         *  the primary, `noticed` maps to noticedBefore, eventId synthesized from the row id. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE tag_events_v2 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventId TEXT NOT NULL, tsEvent TEXT NOT NULL, tsEntered TEXT NOT NULL,
                        primaryState TEXT NOT NULL, secondaries TEXT NOT NULL,
                        intensity INTEGER, confidence INTEGER, noticedBefore INTEGER,
                        note TEXT, source TEXT NOT NULL, flagRef TEXT, revises INTEGER,
                        uploaded INTEGER NOT NULL)"""
                )
                db.execSQL(
                    """INSERT INTO tag_events_v2
                       (eventId, tsEvent, tsEntered, primaryState, secondaries, noticedBefore,
                        note, source, uploaded)
                       SELECT 'v1-' || id, ts, ts, state, '', noticed, note, source, uploaded
                       FROM tag_events"""
                )
                db.execSQL("DROP TABLE tag_events")
                db.execSQL("ALTER TABLE tag_events_v2 RENAME TO tag_events")
                db.execSQL(
                    """CREATE TABLE flag_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ts TEXT NOT NULL, pkg TEXT NOT NULL, title TEXT, text TEXT,
                        kind TEXT NOT NULL, uploaded INTEGER NOT NULL)"""
                )
                db.execSQL(
                    """CREATE TABLE raw_batches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ts TEXT NOT NULL, payload TEXT NOT NULL, uploaded INTEGER NOT NULL)"""
                )
            }
        }

        @Volatile private var instance: TagDb? = null
        fun get(context: Context): TagDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TagDb::class.java, "tags.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
