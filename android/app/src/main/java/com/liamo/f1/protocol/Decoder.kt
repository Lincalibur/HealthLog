package com.liamo.f1.protocol

import java.time.LocalDateTime

/** A minute-resolution timestamp as the watch stores it (local time). */
data class WatchTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int) {
    fun toLocalDateTime(): LocalDateTime? = runCatching { LocalDateTime.of(year, month, day, hour, minute) }.getOrNull()
}

sealed interface WatchEvent {
    data class Battery(val percent: Int, val charging: Boolean) : WatchEvent
    data class DeviceInfo(val firmware: String, val model: Int?) : WatchEvent

    /** [live] is false for a single (spot) measurement. */
    data class HeartRate(val bpm: Int, val live: Boolean) : WatchEvent
    data class SpO2(val percent: Int, val live: Boolean) : WatchEvent

    /** Systolic/diastolic order is still unverified on the F1. */
    data class BloodPressure(val value1: Int, val value2: Int, val live: Boolean) : WatchEvent

    /** A 0x31 reply with value 0: the sensor is still measuring. */
    data class Measuring(val kind: Int) : WatchEvent

    /** 51 08: today's totals. [other] is a second 24-bit counter (distance or calories). */
    data class TodayTotals(val steps: Int, val other: Int) : WatchEvent

    data class HistoryHeartRate(val time: WatchTime, val bpm: Int) : WatchEvent
    data class HistorySpO2(val time: WatchTime, val percent: Int) : WatchEvent
    data class HistoryBloodPressure(val time: WatchTime, val value1: Int, val value2: Int) : WatchEvent
    data class HistorySteps(val time: WatchTime, val steps: Int) : WatchEvent

    data class FindPhone(val start: Boolean) : WatchEvent

    /** `EA ... 0B ...`: answer with [Commands.HEARTBEAT_REPLY]. */
    data object Heartbeat : WatchEvent

    /** Anything not understood yet. Always log these: they are how new frames get decoded. */
    class Unknown(val raw: ByteArray) : WatchEvent {
        override fun equals(other: Any?) = other is Unknown && other.raw.contentEquals(raw)
        override fun hashCode() = raw.contentHashCode()
        override fun toString() = "Unknown(${raw.toHex()})"
    }
}

/** Decode one complete frame (after [Reassembler]). Never throws. */
fun decode(f: ByteArray): WatchEvent {
    if (f.size < 6) return WatchEvent.Unknown(f)
    if (f.u8(0) == HEADER_EA) {
        return if (f.u8(4) == 0x0B) WatchEvent.Heartbeat else WatchEvent.Unknown(f)
    }
    if (f.u8(0) != HEADER_AB) return WatchEvent.Unknown(f)
    val cmd = f.u8(4)
    val kind = f.u8(5)
    val a = f.copyOfRange(6, f.size)
    fun arg(i: Int) = a.u8(i)

    return when {
        cmd == 0x91 && a.size >= 2 -> WatchEvent.Battery(percent = arg(1), charging = arg(0) != 0)

        cmd == 0x92 && a.size >= 2 -> {
            // Wearfit Pro ConnectAnalyzer.getBandVersionInfo.
            var model: Int? = if (a.size > 2) arg(2) else null
            if (a.size > 4 && (arg(3) != 0 || arg(4) != 0)) model = (arg(3) and 0x7F) * 256 + arg(4)
            WatchEvent.DeviceInfo(firmware = "%d.%02d".format(arg(0), arg(1)), model = model)
        }

        // Gadgetbridge-style live HR. The F1 never sends it, but other WearFit watches do.
        cmd == 0x84 && a.isNotEmpty() -> when (val hr = arg(0)) {
            0, 0xFF -> WatchEvent.Measuring(Measure.LIVE_HR)
            else -> WatchEvent.HeartRate(hr, live = true)
        }

        // Confirmed on the F1 (fw 3.24): AB 00 05 FF 31 <kind> 00 <value>.
        cmd == 0x31 -> decodeMeasure(kind, a, f)

        cmd == 0x51 && kind == 0x08 && a.size >= 6 -> WatchEvent.TodayTotals(
            steps = (arg(0) shl 16) or (arg(1) shl 8) or arg(2),
            other = (arg(3) shl 16) or (arg(4) shl 8) or arg(5),
        )
        cmd == 0x51 && kind == 0x11 && a.size >= 6 -> WatchEvent.HistoryHeartRate(watchTime(a), arg(5))
        cmd == 0x51 && kind == 0x12 && a.size >= 6 -> WatchEvent.HistorySpO2(watchTime(a), arg(5))
        cmd == 0x51 && kind == 0x14 && a.size >= 7 -> WatchEvent.HistoryBloodPressure(watchTime(a), arg(5), arg(6))
        cmd == 0x51 && kind == 0x20 && a.size >= 7 ->
            WatchEvent.HistorySteps(WatchTime(2000 + arg(0), arg(1), arg(2), arg(3), 0), (arg(5) shl 8) or arg(6))

        cmd == 0x7D && a.isNotEmpty() -> WatchEvent.FindPhone(start = arg(0) != 0)

        else -> WatchEvent.Unknown(f)
    }
}

private fun decodeMeasure(kind: Int, a: ByteArray, f: ByteArray): WatchEvent {
    if (a.size < 2) return WatchEvent.Unknown(f)
    val v = a.u8(1)
    return when (kind) {
        Measure.SINGLE_HR, Measure.LIVE_HR ->
            if (v == 0) WatchEvent.Measuring(kind) else WatchEvent.HeartRate(v, live = kind == Measure.LIVE_HR)
        Measure.SINGLE_SPO2, Measure.LIVE_SPO2 ->
            if (v == 0) WatchEvent.Measuring(kind) else WatchEvent.SpO2(v, live = kind == Measure.LIVE_SPO2)
        Measure.SINGLE_BP, Measure.LIVE_BP -> when {
            a.size < 3 -> WatchEvent.Unknown(f)
            v == 0 && a.u8(2) == 0 -> WatchEvent.Measuring(kind)
            else -> WatchEvent.BloodPressure(v, a.u8(2), live = kind == Measure.LIVE_BP)
        }
        else -> WatchEvent.Unknown(f)
    }
}

private fun watchTime(a: ByteArray) = WatchTime(2000 + a.u8(0), a.u8(1), a.u8(2), a.u8(3), a.u8(4))

/** Contents of the WeChat pedometer characteristic FEA1. */
data class Pedometer(val steps: Int?, val distance: Int?, val calories: Int?)

/** Flag byte (bit0 steps, bit1 distance, bit2 calories), then uint24 little-endian fields in that order. */
fun decodePedometer(v: ByteArray): Pedometer? {
    if (v.size < 4) return null
    val flag = v.u8(0)
    var i = 1
    fun next(bit: Int): Int? {
        if (flag and bit == 0 || i + 3 > v.size) return null
        val out = v.u8(i) or (v.u8(i + 1) shl 8) or (v.u8(i + 2) shl 16)
        i += 3
        return out
    }
    return Pedometer(steps = next(1), distance = next(2), calories = next(4))
}
