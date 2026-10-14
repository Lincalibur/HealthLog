package com.liamo.f1.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Golden tests. Expected bytes come from the real watch (report 2026-10-14) or from
 * tools/genius_f1.py, the reference implementation this package is ported from.
 */
class ProtocolTest {
    private fun hex(s: String) = hexToBytes(s)

    // ---- Commands: identical to genius_f1.py's output --------------------------------

    @Test fun batteryRequest() = assertEquals("ab0003ff9180", Commands.battery().toHex())
    @Test fun deviceInfoRequest() = assertEquals("ab0004ff928001", Commands.deviceInfo().toHex())
    @Test fun findRequest() = assertEquals("ab0003ff7180", Commands.find().toHex())

    @Test fun liveHeartRateOnOff() {
        assertEquals("ab0004ff310a01", Commands.measure(Measure.LIVE_HR, true).toHex())
        assertEquals("ab0004ff310a00", Commands.measure(Measure.LIVE_HR, false).toHex())
    }

    @Test fun setTime() = assertEquals(
        "ab000bff93800007ea0a0e0c1e05",
        Commands.setTime(LocalDateTime.of(2026, 10, 14, 12, 30, 5)).toHex(),
    )

    @Test fun syncHistorySince2000() = assertEquals(
        "ab000eff51800000010100000001010000",
        Commands.syncHistory(LocalDateTime.of(2000, 1, 1, 0, 0)).toHex(),
    )

    @Test fun notification() = assertEquals("ab0007ff728003024869", Commands.notify("Hi").toHex())

    @Test fun heartbeatReply() = assertEquals("ea0003000b02", Commands.HEARTBEAT_REPLY.toHex())

    // ---- Decoding frames captured from the real watch ---------------------------------

    @Test fun battery80() =
        assertEquals(WatchEvent.Battery(percent = 80, charging = false), decode(hex("ab0005ff91800050")))

    @Test fun liveHeartRate86() =
        assertEquals(WatchEvent.HeartRate(86, live = true), decode(hex("ab0005ff310a0056")))

    @Test fun firmware324Model162() =
        assertEquals(WatchEvent.DeviceInfo("3.24", 162), decode(hex("ab000dff92c00318a200000000000022")))

    @Test fun spo2CutShortStillDecodes() {
        // The 37 % reading from the first report: decoded as-is; the range check rejects it later.
        val e = decode(hex("ab0005ff31110025"))
        assertEquals(WatchEvent.SpO2(37, live = false), e)
        assertTrue(37 !in Ranges.SPO2)
    }

    @Test fun heartRateMeasuring() =
        assertEquals(WatchEvent.Measuring(Measure.LIVE_HR), decode(hex("ab0005ff310a0000")))

    @Test fun todayTotalsAllZero() =
        assertEquals(WatchEvent.TodayTotals(0, 0), decode(hex("ab000dff510800000000000000000000")))

    @Test fun heartbeat() = assertEquals(WatchEvent.Heartbeat, decode(hex("ea0005000b0000")))

    @Test fun bloodPressure() =
        assertEquals(WatchEvent.BloodPressure(118, 76, live = false), decode(hex("ab0006ff312100764c")))

    @Test fun historyHeartRate() = assertEquals(
        WatchEvent.HistoryHeartRate(WatchTime(2026, 10, 14, 9, 30), 72),
        decode(hex("ab0009ff51111a0a0e091e48")),
    )

    @Test fun unknownIsKept() {
        val raw = hex("ab0004ff99800102")
        val e = decode(raw)
        assertTrue(e is WatchEvent.Unknown)
        assertArrayEquals(raw, (e as WatchEvent.Unknown).raw)
    }

    @Test fun shortAndForeignFramesDontThrow() {
        assertTrue(decode(hex("ab00")) is WatchEvent.Unknown)
        assertTrue(decode(hex("0102030405060708")) is WatchEvent.Unknown)
        assertTrue(decode(hex("ab0005ff31")) is WatchEvent.Unknown)
    }

    @Test fun pedometer460Steps() =
        assertEquals(Pedometer(steps = 460, distance = null, calories = null), decodePedometer(hex("01cc0100")))

    @Test fun pedometerAllFields() = assertEquals(
        Pedometer(steps = 249, distance = 180, calories = 9),
        decodePedometer(hex("07f90000b40000090000")),
    )

    @Test fun pedometerTooShort() = assertNull(decodePedometer(hex("01cc")))

    // ---- Splitting and reassembly ----------------------------------------------------

    @Test fun splitMatchesPython() {
        val f = frame(0x72, ByteArray(39) { it.toByte() })
        assertEquals(
            "ab002aff7280000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20212223242526",
            f.toHex(),
        )
        assertEquals(
            listOf("ab002aff7280000102030405060708090a0b0c0d", "000e0f101112131415161718191a1b1c1d1e1f20", "01212223242526"),
            split(f).map { it.toHex() },
        )
    }

    @Test fun chunksNeverExceed20Bytes() {
        for (n in 0..100) split(frame(0x72, ByteArray(n))).forEach { assertTrue(it.size <= CHUNK_SIZE) }
    }

    @Test fun splitReassembleRoundTrip0To100() {
        for (n in 0..100) {
            val f = frame(0x72, ByteArray(n) { (it * 7 + n).toByte() })
            val rx = Reassembler()
            val chunks = split(f)
            val outputs = chunks.map { rx.feed(it) }
            outputs.dropLast(1).forEach { assertNull("length $n: frame finished early", it) }
            assertArrayEquals("length $n", f, outputs.last())
        }
    }

    @Test fun reassemblerPassesForeignPacketsThrough() {
        val rx = Reassembler()
        assertArrayEquals(hex("0102"), rx.feed(hex("0102")))
        assertArrayEquals(hex("ab0005ff91800050"), rx.feed(hex("ab0005ff91800050")))
    }

    @Test fun reassemblerTrimsPadding() {
        assertArrayEquals(hex("ab0005ff91800050"), Reassembler().feed(hex("ab0005ff918000500000")))
    }

    @Test fun hexParsing() {
        assertArrayEquals(hex("ab0003ff7180"), hexToBytes("AB 00 03 FF 71 80"))
        assertArrayEquals(hex("ab0003"), hexToBytes("ab:00:03"))
    }
}
