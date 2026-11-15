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
}
