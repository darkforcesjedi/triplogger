package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tree/document URI doc-id parsing that resolves the SAF journal root to a
 * filesystem path for the DB file. Segments are given in their DECODED form
 * (Uri.pathSegments hands them over already decoded).
 */
class JournalPathTest {

    @Test
    fun treeUriWithSemicolonVolumeSeparator() {
        // content://...documents/tree/primary%3BAndroid%2Fdata%2F...%2FTrips
        val segments = listOf(
            "tree",
            "primary;Android/data/com.mallner.triplogger/files/Trips"
        )
        assertEquals(
            "Android/data/com.mallner.triplogger/files/Trips",
            JournalPath.relativePathFromSegments(segments)
        )
    }

    @Test
    fun documentUriWithColonSeparator() {
        val segments = listOf("document", "primary:TripLogger")
        assertEquals("TripLogger", JournalPath.relativePathFromSegments(segments))
    }

    @Test
    fun treeUriWithTrailingDocumentPrefersDocumentSegment() {
        val segments = listOf(
            "tree",
            "primary;TripLogger",
            "document",
            "primary:TripLogger"
        )
        assertEquals("TripLogger", JournalPath.relativePathFromSegments(segments))
    }

    @Test
    fun rootOfExternalStorageItselfIsNull() {
        assertNull(JournalPath.relativePathFromSegments(listOf("tree", "primary;")))
    }

    @Test
    fun nonPrimaryVolumesAndGarbageResolveToNull() {
        assertNull(JournalPath.relativePathFromSegments(listOf("tree", "secondary;123A:Trips")))
        assertNull(JournalPath.relativePathFromSegments(listOf("tree", "home:Trips")))
        assertNull(JournalPath.relativePathFromSegments(listOf("tree", "noid")))
        assertNull(JournalPath.relativePathFromSegments(listOf("root", "primary;x")))
        assertNull(JournalPath.relativePathFromSegments(listOf("tree")))
        assertNull(JournalPath.relativePathFromSegments(emptyList()))
    }
}
