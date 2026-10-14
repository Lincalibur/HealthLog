package com.liamo.f1.data

import com.liamo.f1.protocol.Ranges
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * The only way readings get into the database. Applies the plan's rules:
 * out-of-range values become `out_of_range` attempts instead of readings, live heart
 * rate keeps one row per 30 s, and timestamps are whole seconds (the day files have
 * second resolution, so two rows in the same second would collide there).
 */
class Store(val db: AppDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val stateLock = Mutex()

    /** Returns the outcome so callers can log it as part of a larger attempt. */
    suspend fun heartRate(bpm: Int, source: String, at: Long = now()): String {
        if (bpm !in Ranges.HEART_RATE) return outOfRange(AttemptKind.HR, at, "bpm=$bpm")
        if (source == Source.LIVE) {
            val bucket = at - at % LIVE_HR_BUCKET_MS
            db.readings().insertOrReplace(Reading(type = ReadingType.HR, takenAt = bucket, value1 = bpm, source = source))
        } else {
            insert(ReadingType.HR, at, bpm, null, source)
        }
        return Outcome.OK
    }

    suspend fun spo2(percent: Int, source: String, at: Long = now()): String {
        if (percent !in Ranges.SPO2) return outOfRange(AttemptKind.SPO2, at, "spo2=$percent")
        insert(ReadingType.SPO2, at, percent, null, source)
        return Outcome.OK
    }

    suspend fun bloodPressure(v1: Int, v2: Int, source: String, at: Long = now()): String {
        if (v1 !in Ranges.BP_VALUE1 || v2 !in Ranges.BP_VALUE2) {
            return outOfRange(AttemptKind.BP, at, "bp=$v1/$v2")
        }
        insert(ReadingType.BP, at, v1, v2, source)
        return Outcome.OK
    }

    suspend fun steps(steps: Int, source: String, at: Long = now()): String {
        if (steps !in Ranges.STEPS) return outOfRange(AttemptKind.STEPS, at, "steps=$steps")
        insert(ReadingType.STEPS, at, steps, null, source)
        return Outcome.OK
    }

    suspend fun battery(percent: Int, source: String, at: Long = now()): String {
        if (percent !in Ranges.BATTERY) return outOfRange(AttemptKind.BATTERY, at, "battery=$percent")
        insert(ReadingType.BATTERY, at, percent, null, source)
        return Outcome.OK
    }

    suspend fun attempt(kind: String, outcome: String, startedAt: Long, detail: String? = null) {
        db.attempts().insert(
            Attempt(
                startedAt = startedAt,
                kind = kind,
                outcome = outcome,
                durationMs = (now() - startedAt).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                detail = detail?.take(500),
            ),
        )
    }

    suspend fun state(): SyncState = db.syncState().get() ?: SyncState()

    suspend fun updateState(change: (SyncState) -> SyncState) = stateLock.withLock {
        db.syncState().put(change(state()))
    }

    /** Keep 90 days locally; only rows that already reached GitHub are ever deleted. */
    suspend fun pruneExported() {
        val before = now() - RETENTION.toMillis()
        db.readings().deleteExportedBefore(before)
        db.attempts().deleteExportedBefore(before)
    }

    private suspend fun insert(type: String, at: Long, v1: Int, v2: Int?, source: String) {
        db.readings().insert(Reading(type = type, takenAt = at - at % 1000, value1 = v1, value2 = v2, source = source))
    }

    private suspend fun outOfRange(kind: String, at: Long, detail: String): String {
        attempt(kind, Outcome.OUT_OF_RANGE, at, detail)
        return Outcome.OUT_OF_RANGE
    }

    companion object {
        const val LIVE_HR_BUCKET_MS = 30_000L
        val RETENTION: Duration = Duration.ofDays(90)
    }
}
