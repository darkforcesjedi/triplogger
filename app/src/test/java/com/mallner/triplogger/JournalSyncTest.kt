package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Folder-name fallback for trips whose start time only survives as a name. */
class JournalSyncTest {

    @Test
    fun parsesWallClockFolderNames() {
        val expected = java.time.ZonedDateTime
            .of(2026, 1, 19, 12, 0, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        assertEquals(expected, JournalSync.parseFolderStartMs("20260119_120000"))
    }

    @Test
    fun rejectsOtherShapes() {
        assertNull(JournalSync.parseFolderStartMs("2026-01-19 12.00.00"))
        assertNull(JournalSync.parseFolderStartMs("trip"))
        assertNull(JournalSync.parseFolderStartMs(""))
    }
}
