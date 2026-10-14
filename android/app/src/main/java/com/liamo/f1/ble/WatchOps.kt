package com.liamo.f1.ble

import com.liamo.f1.data.AttemptKind
import com.liamo.f1.data.Outcome
import com.liamo.f1.data.Source
import com.liamo.f1.data.Store
import com.liamo.f1.protocol.Commands
import com.liamo.f1.protocol.Measure
import com.liamo.f1.protocol.WatchEvent
import com.liamo.f1.protocol.WatchTime
import com.liamo.f1.protocol.decodePedometer
import com.liamo.f1.protocol.toHex
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything the app does with the watch, one function per feature. Each one records
 * an attempt (ok, timeout, no_reply, ...) so the dashboard can show how well it works.
 * All of them expect the caller to hold the connection ([WatchSession.withConnection]).
 */
class WatchOps(private val session: WatchSession, private val store: Store) {
    private val zone get() = ZoneId.systemDefault()

    /** Set the clock at most once per local day: it can reset today's step count. */
    suspend fun setClockIfNeeded(force: Boolean = false): Boolean {
        val today = LocalDate.now(zone).toString()
        if (!force && store.state().lastClockSet == today) return false
        val started = now()
        val outcome = runCatching { session.send(Commands.setTime(LocalDateTime.now(zone))) }
            .fold({ Outcome.OK }, { Outcome.ERROR })
        store.attempt(AttemptKind.CLOCK, outcome, started)
        if (outcome == Outcome.OK) store.updateState { it.copy(lastClockSet = today) }
        return outcome == Outcome.OK
    }

    suspend fun readInfo(): WatchEvent.DeviceInfo? {
        val started = now()
        val info = session.request<WatchEvent.DeviceInfo>(REPLY_TIMEOUT_MS) { session.send(Commands.deviceInfo()) }
        store.attempt(AttemptKind.INFO, if (info != null) Outcome.OK else Outcome.NO_REPLY, started)
        if (info != null) store.updateState { it.copy(firmware = info.firmware) }
        return info
    }

    suspend fun readBattery(): WatchEvent.Battery? {
        val started = now()
        val b = session.request<WatchEvent.Battery>(REPLY_TIMEOUT_MS) { session.send(Commands.battery()) }
        if (b == null) {
            store.attempt(AttemptKind.BATTERY, Outcome.NO_REPLY, started)
        } else if (store.battery(b.percent, Source.SNAPSHOT) == Outcome.OK) {
            store.attempt(AttemptKind.BATTERY, Outcome.OK, started)
        }
        return b
    }

    /** Vibrate. The watch sends no reply, so "ok" means the write went through. */
    suspend fun vibrate() {
        val started = now()
        val outcome = runCatching { session.send(Commands.find()) }.fold({ Outcome.OK }, { Outcome.ERROR })
        store.attempt(AttemptKind.VIBRATE, outcome, started)
    }

    /** Today's running total from FEA1 (read only, never subscribed). */
    suspend fun readSteps(): Int? {
        val started = now()
        val raw = runCatching { session.readPedometer() }.getOrNull()
        val steps = raw?.let(::decodePedometer)?.steps
        when {
            raw == null -> store.attempt(AttemptKind.STEPS, Outcome.NO_REPLY, started, "FEA1 not readable")
            steps == null -> store.attempt(AttemptKind.STEPS, Outcome.ERROR, started, raw.toHex())
            store.steps(steps, Source.SNAPSHOT) == Outcome.OK -> store.attempt(AttemptKind.STEPS, Outcome.OK, started)
        }
        return steps
    }

    /** One short heart-rate sample: live HR on, first value, live HR off. */
    suspend fun heartRateSample(timeoutMs: Long = 30_000): Int? {
        val started = now()
        val hr = try {
            session.request<WatchEvent.HeartRate>(timeoutMs) { session.send(Commands.measure(Measure.LIVE_HR, true)) }
        } finally {
            runCatching { session.send(Commands.measure(Measure.LIVE_HR, false)) }
        }
        if (hr == null) {
            store.attempt(AttemptKind.HR, Outcome.TIMEOUT, started)
        } else if (store.heartRate(hr.bpm, Source.SNAPSHOT) == Outcome.OK) {
            store.attempt(AttemptKind.HR, Outcome.OK, started)
        }
        return hr?.bpm
    }

    /** Experimental: single SpO2. On the first laptop test the value only came after the stop command. */
    suspend fun spotSpO2(): Int? {
        val started = now()
        val e = spot<WatchEvent.SpO2>(Measure.SINGLE_SPO2) ?: run {
            store.attempt(AttemptKind.SPO2, Outcome.TIMEOUT, started); return null
        }
        if (store.spo2(e.percent, Source.SPOT) == Outcome.OK) store.attempt(AttemptKind.SPO2, Outcome.OK, started)
        return e.percent
    }

    /** Experimental: single blood pressure. Which value is systolic is still unverified. */
    suspend fun spotBloodPressure(): WatchEvent.BloodPressure? {
        val started = now()
        val e = spot<WatchEvent.BloodPressure>(Measure.SINGLE_BP) ?: run {
            store.attempt(AttemptKind.BP, Outcome.TIMEOUT, started); return null
        }
        if (store.bloodPressure(e.value1, e.value2, Source.SPOT) == Outcome.OK) {
            store.attempt(AttemptKind.BP, Outcome.OK, started)
        }
        return e
    }

    /**
     * Experimental: ask for history since the last sync (or 7 days) and store every record.
     * So far the F1 has only answered with the all-zero "today" frame.
     */
    suspend fun syncHistory(listenMs: Long = 20_000): Int {
        val started = now()
        val since = store.state().lastHistorySync
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            ?: LocalDateTime.now(zone).minusDays(7)
        var records = 0
        var gotAnything = false
        val unexpected = mutableListOf<String>()
        withTimeoutOrNull(listenMs) {
            session.events
                .onSubscription { session.send(Commands.syncHistory(since)) }
                .collect { e ->
                    gotAnything = true
                    if (storeHistory(e)) records++
                    if (e is WatchEvent.Unknown) unexpected += e.raw.toHex()
                }
        }
        val outcome = if (gotAnything) Outcome.OK else Outcome.NO_REPLY
        store.attempt(
            AttemptKind.HISTORY, outcome, started,
            buildString {
                append("records=$records")
                if (unexpected.isNotEmpty()) append(" unknown=").append(unexpected.joinToString(","))
            },
        )
        if (records > 0) store.updateState { it.copy(lastHistorySync = started) }
        return records
    }

    /** Experimental: show a message on the watch (opcode 72, untested on the F1). */
    suspend fun notify(text: String) {
        val started = now()
        val outcome = runCatching { session.send(Commands.notify(text)) }.fold({ Outcome.OK }, { Outcome.ERROR })
        store.attempt(AttemptKind.NOTIFY, outcome, started, "sent; check the watch screen")
    }

    /** Clock (once a day), device info and battery: what every connection starts with. */
    suspend fun onConnected() {
        setClockIfNeeded()
        readInfo()
        readBattery()
    }

    private suspend inline fun <reified T : WatchEvent> spot(kind: Int): T? {
        // Wait up to 60 s for a non-zero value...
        val first = session.request<T>(SPOT_TIMEOUT_MS) { session.send(Commands.measure(kind, true)) }
        if (first != null) {
            runCatching { session.send(Commands.measure(kind, false)) }
            return first
        }
        // ...then stop. The F1 has answered only after the stop command before.
        return session.request<T>(AFTER_STOP_MS) { session.send(Commands.measure(kind, false)) }
    }

    private suspend fun storeHistory(e: WatchEvent): Boolean {
        fun at(t: WatchTime) = t.toLocalDateTime()?.atZone(zone)?.toInstant()?.toEpochMilli()
        return when (e) {
            is WatchEvent.HistoryHeartRate -> at(e.time)?.let { store.heartRate(e.bpm, Source.HISTORY, it) } == Outcome.OK
            is WatchEvent.HistorySpO2 -> at(e.time)?.let { store.spo2(e.percent, Source.HISTORY, it) } == Outcome.OK
            is WatchEvent.HistoryBloodPressure ->
                at(e.time)?.let { store.bloodPressure(e.value1, e.value2, Source.HISTORY, it) } == Outcome.OK
            // Hourly step records are increments, not the daily total the steps readings hold:
            // keep them out of readings until the format is confirmed on a real capture.
            is WatchEvent.HistorySteps -> false
            is WatchEvent.TodayTotals -> e.steps > 0 && store.steps(e.steps, Source.HISTORY) == Outcome.OK
            else -> false
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val REPLY_TIMEOUT_MS = 5_000L
        const val SPOT_TIMEOUT_MS = 60_000L
        const val AFTER_STOP_MS = 5_000L
    }
}
