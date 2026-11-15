package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Legacy trip.properties -> DB row mapping (migration). Covers the current
 * finalizer output, pre-algo trips with only the legacy distance_m, and the
 * "never lose a key" ext parking for unknown or rejected values.
 */
class PropertiesImportTest {

    private val now = 1_700_000_000_000L

    private fun import(props: Map<String, String>, folder: String = "20261105_123113", state: String = "archive") =
        when (val r = PropertiesImport.import(folder, state, props, now)) {
            is PropertiesImport.Result.Invalid -> throw AssertionError("invalid: ${r.reason}")
            is PropertiesImport.Result.Success -> r.trip
        }

    @Test
    fun fullConvertedTripMapsColumnsMetricsAndOutputs() {
        val t = import(
            mapOf(
                "trip_id" to "20261105_123113",
                "start_ms" to "1762367473000",
                "end_ms" to "1762371073000",
                "pids" to "0D, 0C ,",
                "trigger" to "bluetooth",
                "tz_id" to "America/Los_Angeles",
                "tz_offset_min" to "-480",
                "converted" to "1",
                "converted_at" to "1762371080000",
                "app_version" to "1.2",
                "entries" to "4211",
                "segments" to "2",
                "distance_m" to "15002.4",
                "distance_algo" to "1",
                "distance_track_m" to "15002.4",
                "distance_gps_speed_m" to "15120.0",
                "distance_obd_m" to "15033.8",
                "distance_odo_m" to "15010.0",
                "odo_start_km" to "57215.8",
                "odo_end_km" to "57230.8",
                "distance_src" to "odo",
                "max_speed_mps" to "27.49",
                "kmz_day" to "2026-11-05",
                "ods_file" to "Trip 2026-11-05 12.31.ods",
                "title" to "Work run",
                "place_start" to "Home",
                "place_end" to "Office"
            )
        )
        assertEquals("t1762367473000", t.id)
        assertEquals("20261105_123113", t.folder)
        assertEquals("archive", t.state)
        assertEquals(1762367473000L, t.columns["start_ms"])
        assertEquals(1762371073000L, t.columns["end_ms"])
        assertEquals(4211, t.columns["entries"])
        assertEquals(2, t.columns["segments"])
        assertEquals("0D,0C", t.columns["pids"])
        assertEquals("odo", t.columns["distance_source"])
        assertEquals("Work run", t.columns["title"])
        assertEquals("Home", t.columns["place_start"])
        assertEquals(-480, t.columns["tz_offset_min"])

        val distances = t.metrics.filter { it.kind == "distance_m" }.associate { it.source to it.value }
        assertEquals(
            mapOf(
                "track" to 15002.4,
                "gps_speed" to 15120.0,
                "obd" to 15033.8,
                "odo" to 15010.0
            ),
            distances
        )
        // Legacy distance_m must not duplicate the explicit track estimate.
        assertEquals(4, t.metrics.count { it.kind == "distance_m" })
        val odo = t.metrics.filter { it.kind == "odo_km" }.associate { it.source to it.value }
        assertEquals(mapOf("start" to 57215.8, "end" to 57230.8), odo)
        assertEquals(27.49, t.metrics.first { it.kind == "max_speed_mps" }.value, 1e-9)

        assertEquals(
            setOf("kmz_day" to "2026-11-05.kmz", "ods" to "Trip 2026-11-05 12.31.ods"),
            t.outputs.map { it.kind to it.name }.toSet()
        )

        // Unknown / informational keys are parked, never dropped.
        assertEquals("20261105_123113", t.ext["trip_id"])
        assertEquals("1", t.ext["distance_algo"])
        assertTrue("converted must be dropped", "converted" !in t.ext)
        assertTrue(
            "mapped columns/outputs must not leak into ext",
            t.ext.keys.none { it in setOf("title", "end_ms", "pids", "distance_src", "kmz_day", "ods_file") }
        )
    }

    @Test
    fun legacyTripDistanceOnlyFillsTrack() {
        val t = import(
            mapOf(
                "start_ms" to "1762000000000",
                "distance_m" to "8123.5",
                "max_speed_mps" to "18.2"
            )
        )
        val distances = t.metrics.filter { it.kind == "distance_m" }
        assertEquals(1, distances.size)
        assertEquals("track", distances.first().source)
        assertEquals(8123.5, distances.first().value, 1e-9)
        assertNull(t.columns["distance_source"])
    }

    @Test
    fun invalidValuesAreParkedNotLost() {
        val t = import(
            mapOf(
                "start_ms" to "1762000000000",
                "distance_src" to "moon",
                "distance_odo_note" to "roll back detected",
                "future_key" to "whatever"
            )
        )
        assertNull(t.columns["distance_source"])
        assertEquals("moon", t.ext["distance_src"])
        assertEquals("roll back detected", t.ext["distance_odo_note"])
        assertEquals("whatever", t.ext["future_key"])
    }

    @Test
    fun emptyValuesBecomeNull() {
        val t = import(
            mapOf(
                "start_ms" to "1762000000000",
                "title" to "   ",
                "pids" to " , ",
                "end_ms" to ""
            )
        )
        assertNull(t.columns["title"])
        assertNull(t.columns["pids"])
        assertNull(t.columns["end_ms"])
    }

    @Test
    fun rejectsBadInput() {
        assertTrue(PropertiesImport.import("f", "archive", emptyMap(), now)
            is PropertiesImport.Result.Invalid)
        assertTrue(PropertiesImport.import("f", "archive", mapOf("start_ms" to "0"), now)
            is PropertiesImport.Result.Invalid)
        assertTrue(PropertiesImport.import("f", "somewhere", mapOf("start_ms" to "5"), now)
            is PropertiesImport.Result.Invalid)
        assertTrue(PropertiesImport.import("", "archive", mapOf("start_ms" to "5"), now)
            is PropertiesImport.Result.Invalid)
    }
}
