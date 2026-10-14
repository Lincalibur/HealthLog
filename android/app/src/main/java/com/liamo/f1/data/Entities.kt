package com.liamo.f1.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Values of [Reading.type]. */
object ReadingType {
    const val HR = "hr"
    const val STEPS = "steps"
    const val SPO2 = "spo2"
    const val BP = "bp"
    const val BATTERY = "battery"
}

/** Values of [Reading.source]. */
object Source {
    const val LIVE = "live"
    const val SPOT = "spot"
    const val HISTORY = "history"
    const val SNAPSHOT = "snapshot"
}

/**
 * One reading of any kind. The unique index makes re-inserting the same reading a no-op,
 * so a history sync that returns the same records again adds nothing.
 */
@Entity(
    tableName = "readings",
    indices = [Index(value = ["type", "takenAt", "source"], unique = true), Index("exported")],
)
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    /** Epoch ms, UTC: when the watch measured it, or when the phone received a live value. */
    val takenAt: Long,
    /** bpm, steps, %, or the first blood-pressure value. */
    val value1: Int,
    /** Only for blood pressure. */
    val value2: Int? = null,
    val source: String,
    /** Set only after the pull request carrying it exists. */
    @ColumnInfo(defaultValue = "0") val exported: Boolean = false,
)

/** Values of [Attempt.kind]; must match schema/attempts.schema.json. */
object AttemptKind {
    const val CONNECT = "connect"
    const val BATTERY = "battery"
    const val INFO = "info"
    const val CLOCK = "clock"
    const val VIBRATE = "vibrate"
    const val STEPS = "steps"
    const val HR = "hr"
    const val SPO2 = "spo2"
    const val BP = "bp"
    const val HISTORY = "history"
    const val NOTIFY = "notify"
}

/** Values of [Attempt.outcome]; must match schema/attempts.schema.json. */
object Outcome {
    const val OK = "ok"
    const val TIMEOUT = "timeout"
    const val OUT_OF_RANGE = "out_of_range"
    const val NO_REPLY = "no_reply"
    const val ERROR = "error"
}

/** One row per measurement or sync the app tries, successful or not. Powers the reliability charts. */
@Entity(tableName = "attempts", indices = [Index("exported"), Index("startedAt")])
data class Attempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val kind: String,
    val outcome: String,
    val durationMs: Int,
    /** Raw reply hex for anything unexpected, so new frames can be decoded later. */
    val detail: String? = null,
    @ColumnInfo(defaultValue = "0") val exported: Boolean = false,
)

/** A single row (id = 0) of bookkeeping. */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: Int = 0,
    /** Epoch ms of the last history request; sent as the "since" date of the next 51 request. */
    val lastHistorySync: Long? = null,
    /** Local date (yyyy-MM-dd) the watch clock was last set. Setting it can reset today's steps. */
    val lastClockSet: String? = null,
    val lastExport: Long? = null,
    val lastPullRequestUrl: String? = null,
    val lastExportError: String? = null,
    /** Last firmware seen, e.g. "3.24". Goes in the day files; it describes the model, not the unit. */
    val firmware: String? = null,
    /** Consecutive failed connects; drives the 1/5/15 min backoff and the "watch busy" hint. */
    val connectFailures: Int = 0,
    val lastConnectError: String? = null,
)
