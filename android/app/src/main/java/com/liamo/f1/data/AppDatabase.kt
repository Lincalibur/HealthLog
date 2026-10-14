package com.liamo.f1.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Reading::class, Attempt::class, SyncState::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao
    abstract fun attempts(): AttemptDao
    abstract fun syncState(): SyncStateDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "f1.db").build()
    }
}
