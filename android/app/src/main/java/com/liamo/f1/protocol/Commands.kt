package com.liamo.f1.protocol

import java.time.LocalDateTime
import java.util.UUID

/** Sub-types for the 0x31 measure command (Wearfit Pro setOnceOrRealTimeMeasure). */
object Measure {
    const val SINGLE_HR = 0x09
    const val LIVE_HR = 0x0A
    const val SINGLE_SPO2 = 0x11
    const val LIVE_SPO2 = 0x12
    const val SINGLE_BP = 0x21
    const val LIVE_BP = 0x22
}

/**
 * Phone -> watch frames. Only commands that are safe to send: factory reset (0x23)
 * and reboot (0xFF) are deliberately left out of the app.
 */
object Commands {
    /** AB 00 03 FF 91 80. The F1 answers 91 80 <charging> <percent>. */
    fun battery(): ByteArray = frame(0x91)

    /** AB 00 04 FF 92 80 01: firmware and model (the F1 replies with kind C0). */
    fun deviceInfo(): ByteArray = frame(0x92, bytesOf(0x01))

    /** Local wall-clock time, year big-endian. Can reset today's steps, so send at most once a day. */
    fun setTime(now: LocalDateTime): ByteArray = frame(
        0x93,
        bytesOf(0x00, now.year shr 8, now.year and 0xFF, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second),
    )

    /** AB 00 03 FF 71 80: vibrate the watch. */
    fun find(): ByteArray = frame(0x71)

    /** AB 00 04 FF 31 <kind> <on>. */
    fun measure(kind: Int, on: Boolean): ByteArray = frame(0x31, bytesOf(if (on) 1 else 0), kind = kind)

    /** Request stored history newer than [since] (Gadgetbridge CMD_REQUEST_FITNESS). Experimental on the F1. */
    fun syncHistory(since: LocalDateTime): ByteArray {
        val t = bytesOf(since.year - 2000, since.monthValue, since.dayOfMonth, since.hour, since.minute)
        return frame(0x51, bytesOf(0x00) + t + t)
    }

    /** Push a notification. [source] 0x03 = SMS; see tools/PROTOCOL.md for others. Untested on the F1. */
    fun notify(text: String, source: Int = 0x03): ByteArray =
        frame(0x72, bytesOf(source, 0x02) + text.toByteArray(Charsets.UTF_8))

    /** Reply to the newer-firmware heartbeat (`EA ... 0B ...`). */
    val HEARTBEAT_REPLY: ByteArray get() = bytesOf(0xEA, 0x00, 0x03, 0x00, 0x0B, 0x02)
}

object Uuids {
    /** Nordic UART. Only visible after connecting; the advert carries FEE7 instead. */
    val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val UART_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val UART_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** WeChat pedometer service, advertised by the watch. */
    val PEDOMETER_SERVICE: UUID = UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb")

    /** Today's steps. Read it; never enable its indications (that wedged the watch on the laptop). */
    val PEDOMETER_STEPS: UUID = UUID.fromString("0000fea1-0000-1000-8000-00805f9b34fb")
}

/**
 * Plausible values. Anything outside is logged as an `out_of_range` attempt and kept
 * out of readings. scripts/build_summary.py --check applies the same rules in CI.
 */
object Ranges {
    val HEART_RATE = 30..220
    val SPO2 = 70..100
    val STEPS = 0..200_000
    val BATTERY = 0..100
    val BP_VALUE1 = 60..260
    val BP_VALUE2 = 30..160
}
