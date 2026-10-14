package com.liamo.f1.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    /** Returns -1 when an identical reading (type, takenAt, source) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reading: Reading): Long

    /** Live heart rate: keep the latest value of each 30 s bucket. Replacing also clears `exported`. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(reading: Reading): Long

    @Query("SELECT * FROM readings WHERE exported = 0")
    suspend fun unexported(): List<Reading>

    @Query("SELECT * FROM readings WHERE takenAt >= :from AND takenAt < :to ORDER BY takenAt, type, source")
    suspend fun between(from: Long, to: Long): List<Reading>

    @Query("UPDATE readings SET exported = 1 WHERE id IN (:ids)")
    suspend fun markExported(ids: List<Long>)

    @Query("DELETE FROM readings WHERE exported = 1 AND takenAt < :before")
    suspend fun deleteExportedBefore(before: Long): Int

    @Query("SELECT * FROM readings WHERE type = :type ORDER BY takenAt DESC LIMIT 1")
    fun latest(type: String): Flow<Reading?>

    @Query("SELECT * FROM readings WHERE type = :type AND takenAt >= :from ORDER BY takenAt")
    fun since(type: String, from: Long): Flow<List<Reading>>

    @Query("SELECT MAX(value1) FROM readings WHERE type = 'steps' AND takenAt >= :from")
    fun maxStepsSince(from: Long): Flow<Int?>

    @Query("SELECT COUNT(*) FROM readings")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM readings WHERE exported = 0")
    fun unexportedCount(): Flow<Int>
}

@Dao
interface AttemptDao {
    @Insert
    suspend fun insert(attempt: Attempt): Long

    @Query("SELECT * FROM attempts WHERE exported = 0")
    suspend fun unexported(): List<Attempt>

    @Query("SELECT * FROM attempts WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt, kind")
    suspend fun between(from: Long, to: Long): List<Attempt>

    @Query("UPDATE attempts SET exported = 1 WHERE id IN (:ids)")
    suspend fun markExported(ids: List<Long>)

    @Query("DELETE FROM attempts WHERE exported = 1 AND startedAt < :before")
    suspend fun deleteExportedBefore(before: Long): Int

    @Query("SELECT * FROM attempts ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Attempt>>
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncState?

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun observe(): Flow<SyncState?>

    @Upsert
    suspend fun put(state: SyncState)
}
