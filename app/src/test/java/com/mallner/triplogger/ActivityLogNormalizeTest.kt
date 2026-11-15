package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLogNormalizeTest {

    @Test
    fun folderStyleTripIdsBecomeReadable() {
        assertEquals(
            "trip_recovered 2026-11-06 22:19:01 next",
            ActivityLog.normalizeTimestamps("trip_recovered 20261106_221901 next")
        )
    }

    @Test
    fun dotLabelTripIdsBecomeReadable() {
        assertEquals(
            "converted engine-2026-11-06 22:19:01.ods",
            ActivityLog.normalizeTimestamps("converted engine-2026-11-06 22.19.01.ods")
        )
    }

    @Test
    fun unrelatedNumbersStayUntouched() {
        assertEquals("1600x900 pixels", ActivityLog.normalizeTimestamps("1600x900 pixels"))
        assertEquals("entries=25 threshold=20", ActivityLog.normalizeTimestamps("entries=25 threshold=20"))
        assertEquals("202611061234567", ActivityLog.normalizeTimestamps("202611061234567"))
        assertEquals("", ActivityLog.normalizeTimestamps(""))
    }

    @Test
    fun spoolLineRoundTripsThroughParseLine() {
        val line = org.json.JSONObject()
            .put("t", 1794726640675L)
            .put("e", "db_reconciled")
            .put("m", "folder 20261115_020936 revived")
            .put("entries", "3")
            .toString()
        val event = ActivityLog.parseLine(line)!!
        assertEquals(1794726640675L, event.timeMs)
        assertEquals("db_reconciled", event.event)
        assertEquals("folder 2026-11-15 02:09:36 revived", event.message)
        assertEquals(mapOf("entries" to "3"), event.fields)
    }

    @Test
    fun rawParseKeepsFolderTokensIntactForStorage() {
        val line = org.json.JSONObject()
            .put("t", 1L).put("e", "trip_recovered").put("m", "20261115_020936")
            .toString()
        assertEquals("20261115_020936", ActivityLog.parseLine(line, normalize = false)!!.message)
    }

    @Test
    fun garbageLinesAreDropped() {
        org.junit.Assert.assertNull(ActivityLog.parseLine("not json"))
        org.junit.Assert.assertNull(ActivityLog.parseLine("""{"e":"no time"}"""))
    }

    @Test
    fun databaseRowMapsWithNormalizedFields() {
        val row = TripMetadataStore.ActivityRow(
            id = 7, ts = 100L, event = "trip_recovered",
            tripId = "t123", message = "20261114_222402",
            fields = """{"entries":"12"}"""
        )
        val event = ActivityLog.rowToEvent(row)
        assertEquals("2026-11-14 22:24:02", event.message)
        assertEquals(mapOf("entries" to "12"), event.fields)
    }

    @Test
    fun rowWithoutFieldsJsonStillMaps() {
        val row = TripMetadataStore.ActivityRow(9, 100L, "boot", null, null, "broken{")
        val event = ActivityLog.rowToEvent(row)
        assertEquals("", event.message)
        assertEquals(emptyMap<String, String>(), event.fields)
    }
}
