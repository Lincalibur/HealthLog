package com.liamo.f1.sync

import com.liamo.f1.data.Attempt
import com.liamo.f1.data.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class JsonExporterTest {
    private val zone = ZoneId.of("Africa/Johannesburg") // UTC+2, so local and UTC days differ near midnight
    private val exporter = JsonExporter(zone)
    private val day = LocalDate.of(2026, 10, 14)
    private fun at(h: Int, m: Int = 0, s: Int = 0) = ZonedDateTime.of(2026, 10, 14, h, m, s, 0, zone).toInstant().toEpochMilli()

    private val rows = listOf(
        Reading(id = 3, type = "steps", takenAt = at(14), value1 = 249, source = "snapshot"),
        Reading(id = 1, type = "hr", takenAt = at(13, 5, 30), value1 = 86, source = "live"),
        Reading(id = 2, type = "battery", takenAt = at(14), value1 = 80, source = "snapshot"),
        Reading(id = 4, type = "bp", takenAt = at(15), value1 = 118, value2 = 76, source = "spot"),
    )

    @Test fun pathsUseLocalDate() {
        assertEquals("data/readings/2026/10/2026-10-14.json", exporter.readingsPath(day))
        assertEquals("data/attempts/2026/10/2026-10-14.json", exporter.attemptsPath(day))
        // 00:30 local on the 15th is still the 14th in UTC; the file goes by the phone's day.
        val earlyMorning = ZonedDateTime.of(2026, 10, 15, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 15), exporter.dayOf(earlyMorning))
    }

    @Test fun boundsCoverTheLocalDay() {
        val (from, to) = exporter.bounds(day)
        assertEquals(24 * 3600 * 1000L, to - from)
        assertEquals("2026-10-13T22:00:00Z", JsonExporter.utc(from))
    }

    @Test fun readingsFileIsSortedUtcAndHasNoNulls() {
        val text = exporter.readingsFile(day, "3.24", rows)
        val expected = """
            {
                "date": "2026-10-14",
                "device": {
                    "name": "F1",
                    "firmware": "3.24"
                },
                "readings": [
                    {
                        "t": "2026-10-14T11:05:30Z",
                        "type": "hr",
                        "v": 86,
                        "src": "live"
                    },
                    {
                        "t": "2026-10-14T12:00:00Z",
                        "type": "battery",
                        "v": 80,
                        "src": "snapshot"
                    },
                    {
                        "t": "2026-10-14T12:00:00Z",
                        "type": "steps",
                        "v": 249,
                        "src": "snapshot"
                    },
                    {
                        "t": "2026-10-14T13:00:00Z",
                        "type": "bp",
                        "v": 118,
                        "v2": 76,
                        "src": "spot"
                    }
                ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, text)
        File("build/test-results/sample-readings-2026-10-14.json").apply { parentFile?.mkdirs() }.writeText(text)
    }

    @Test fun reExportIsStable() {
        val first = exporter.readingsFile(day, "3.24", rows)
        assertEquals(first, exporter.readingsFile(day, "3.24", rows.reversed()))
        assertEquals(first, exporter.readingsFile(day, "3.24", rows, existing = first))
    }

    @Test fun mergeKeepsRowsOnlyInTheRepo() {
        val repoCopy = exporter.readingsFile(day, "3.24", rows)
        val pruned = rows.filter { it.type != "bp" }
        val merged = exporter.readingsFile(day, "3.24", pruned + Reading(type = "hr", takenAt = at(16), value1 = 70, source = "snapshot"), repoCopy)
        assertTrue(merged.contains("\"bp\""))
        assertTrue(merged.contains("\"v\": 70"))
    }

    @Test fun localValueWinsOnSameKey() {
        val repoCopy = exporter.readingsFile(day, null, rows)
        val updated = rows.map { if (it.type == "hr") it.copy(value1 = 90) else it }
        val merged = exporter.readingsFile(day, null, updated, repoCopy)
        assertTrue(merged.contains("\"v\": 90"))
        assertFalse(merged.contains("\"v\": 86"))
        assertFalse("no firmware key when unknown", merged.contains("firmware"))
    }

    @Test fun garbageInRepoIsIgnored() {
        assertEquals(exporter.readingsFile(day, "3.24", rows), exporter.readingsFile(day, "3.24", rows, "not json"))
    }

    @Test fun attemptsFile() {
        val attempts = listOf(
            Attempt(startedAt = at(14, 0, 15), kind = "spo2", outcome = "out_of_range", durationMs = 25_000, detail = "ab0005ff31110025"),
            Attempt(startedAt = at(14), kind = "connect", outcome = "ok", durationMs = 2140),
        )
        val text = exporter.attemptsFile(day, attempts)
        assertTrue(text.indexOf("connect") < text.indexOf("spo2"))
        assertFalse(text.contains("null"))
        assertEquals(text, exporter.attemptsFile(day, attempts, existing = text))
        File("build/test-results/sample-attempts-2026-10-14.json").writeText(text)
    }
}
