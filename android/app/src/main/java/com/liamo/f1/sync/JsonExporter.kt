package com.liamo.f1.sync

import com.liamo.f1.data.Attempt
import com.liamo.f1.data.Reading
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
data class DeviceJson(val name: String = "F1", val firmware: String? = null)

@Serializable
data class ReadingJson(val t: String, val type: String, val v: Int, val v2: Int? = null, val src: String)

@Serializable
data class ReadingsDay(val date: String, val device: DeviceJson, val readings: List<ReadingJson>)

@Serializable
data class AttemptJson(val t: String, val kind: String, val outcome: String, val ms: Int, val detail: String? = null)

@Serializable
data class AttemptsDay(val date: String, val attempts: List<AttemptJson>)

/**
 * Builds the day files under data/ (see the JSON Schemas in schema/). Files are grouped by the
 * phone's local date, timestamps are UTC, and rows are sorted, so re-exporting
 * unchanged data produces no diff. No device address or serial ever goes in.
 */
class JsonExporter(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true // device.name is a default and the schema requires it
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun dayOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** [start, end) of a local day in epoch ms. */
    fun bounds(day: LocalDate): Pair<Long, Long> =
        day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun readingsPath(day: LocalDate) = "data/readings/%04d/%02d/%s.json".format(day.year, day.monthValue, day)
    fun attemptsPath(day: LocalDate) = "data/attempts/%04d/%02d/%s.json".format(day.year, day.monthValue, day)

    /**
     * The whole day's file from [rows] (every row for that day, exported or not), merged
     * with the file already in the repo ([existing]) so that pruning old rows on the
     * phone can never shrink it. Local rows win on the same (t, type, src).
     */
    fun readingsFile(day: LocalDate, firmware: String?, rows: List<Reading>, existing: String? = null): String {
        val merged = LinkedHashMap<Triple<String, String, String>, ReadingJson>()
        existing?.let { runCatching { json.decodeFromString<ReadingsDay>(it) }.getOrNull() }
            ?.readings?.forEach { merged[Triple(it.t, it.type, it.src)] = it }
        for (r in rows) {
            val j = ReadingJson(t = utc(r.takenAt), type = r.type, v = r.value1, v2 = r.value2, src = r.source)
            merged[Triple(j.t, j.type, j.src)] = j
        }
        val sorted = merged.values.sortedWith(compareBy({ it.t }, { it.type }, { it.src }))
        return json.encodeToString(ReadingsDay.serializer(), ReadingsDay(day.toString(), DeviceJson(firmware = firmware), sorted)) + "\n"
    }

    fun attemptsFile(day: LocalDate, rows: List<Attempt>, existing: String? = null): String {
        val merged = LinkedHashSet<AttemptJson>()
        existing?.let { runCatching { json.decodeFromString<AttemptsDay>(it) }.getOrNull() }?.attempts?.let(merged::addAll)
        rows.mapTo(merged) { AttemptJson(utc(it.startedAt), it.kind, it.outcome, it.durationMs, it.detail) }
        val sorted = merged.sortedWith(compareBy({ it.t }, { it.kind }, { it.outcome }, { it.ms }, { it.detail ?: "" }))
        return json.encodeToString(AttemptsDay.serializer(), AttemptsDay(day.toString(), sorted)) + "\n"
    }

    companion object {
        private val UTC_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
        fun utc(epochMs: Long): String = UTC_SECONDS.format(Instant.ofEpochMilli(epochMs))
    }
}
