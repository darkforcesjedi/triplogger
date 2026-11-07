package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun createAppendAndReadBack() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C", "0D"))

        journal.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        journal.append(GpsRow(2000, 2000, 37.391, -122.081, 41.0, 13.5f, 181f, 5f))
        journal.append(ObdRow(1500, 1000, 37.39, -122.08, 40.0, mapOf("0C" to 1726.0, "0D" to 80.0)))
        journal.append(ObdRow(2500, null, null, null, null, mapOf("0C" to 1800.0, "0D" to null)))
        journal.close()

        val trip = store.orphanOf(journal)
        assertEquals(listOf("0C", "0D"), trip.pids())
        assertEquals(1768798800000L, trip.startMs())

        val gps = trip.readGpsRows()
        assertEquals(2, gps.size)
        assertEquals(37.39, gps[0].latitude, 1e-6)
        assertEquals(13.5f, gps[1].speedMps, 0.001f)

        val obd = trip.readObdRows(trip.pids())
        assertEquals(2, obd.size)
        assertEquals(1726.0, obd[0].values["0C"]!!, 0.001)
        assertEquals(80.0, obd[0].values["0D"]!!, 0.001)
        assertEquals(1800.0, obd[1].values["0C"]!!, 0.001)
        assertEquals(null, obd[1].values["0D"])
        assertEquals(null, obd[1].gpsTime)
    }

    @Test
    fun unfinishedTripsFindsPendingAndFailed() {
        val root = tmp.newFolder("journal2")
        val store = FileJournalStore(root)
        store.createTrip("20260119_060000", 1768798800000L, listOf("0C")).close()
        store.createTrip("20260119_070000", 1768798800000L, listOf("0C")).close()

        // simulate one previous failure
        val trips = store.unfinishedTrips()
        store.relocate(trips.first(), toArchive = false)

        val names = store.unfinishedTrips().map { it.name }
        assertEquals(listOf("20260119_060000", "20260119_070000"), names.sorted())
        assertTrue(java.io.File(root, "failed/20260119_060000/trip.properties").isFile)
    }

    @Test
    fun successRelocationMovesFiles() {
        val root = tmp.newFolder("journal3")
        val store = FileJournalStore(root)
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        journal.close()

        val trip = store.orphanOf(journal)
        store.relocate(trip, toArchive = true)

        assertFalse(java.io.File(root, "pending/20260119_060000").exists())
        assertTrue(java.io.File(root, "archive/20260119_060000/gps-000.csv").isFile)
        assertTrue(java.io.File(root, "archive/20260119_060000/obd-000.csv").isFile)
    }

    @Test
    fun truncatedLinesAreIgnored() {
        val root = tmp.newFolder("journal4")
        val store = FileJournalStore(root)
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        journal.close()
        // simulate a kill mid-write by corrupting the last line
        java.io.File(root, "pending/20260119_060000/gps-000.csv").appendText("1500,1500,37.39\n")

        val trip = store.orphanOf(journal)
        assertEquals(1, trip.readGpsRows().size)
    }

    @Test
    fun splitContinuesSameTrip() {
        val store = FileJournalStore(tmp.newFolder("journal6"))
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))

        journal.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        journal.append(ObdRow(1500, 1000, 37.39, -122.08, 40.0, mapOf("0C" to 1726.0)))
        assertEquals(0, journal.segmentCount)
        assertTrue(journal.approxBytes > 0)

        journal.split() // e.g. segment size cap reached

        journal.append(GpsRow(2000, 2000, 37.392, -122.082, 42.0, 14.5f, 182f, 5f))
        journal.append(ObdRow(2500, 2000, 37.392, -122.082, 42.0, mapOf("0C" to 2200.0)))
        journal.close()

        assertEquals(1, journal.segmentCount)
        // both segments exist on disk
        val trip = store.orphanOf(journal)
        val files = trip.listFileNames()
        assertTrue(files.containsAll(listOf("gps-000.csv", "gps-001.csv", "obd-000.csv", "obd-001.csv")))

        // reading concatenates all segments in order, headers skipped
        val gps = trip.readGpsRows()
        assertEquals(2, gps.size)
        assertEquals(37.39, gps[0].latitude, 1e-6)
        assertEquals(37.392, gps[1].latitude, 1e-6)

        val obd = trip.readObdRows(trip.pids())
        assertEquals(2, obd.size)
        assertEquals(1726.0, obd[0].values["0C"]!!, 0.001)
        assertEquals(2200.0, obd[1].values["0C"]!!, 0.001)
    }

    @Test
    fun legacySingleFileJournalsStillReadable() {
        val root = tmp.newFolder("journal7")
        val dir = java.io.File(root, "pending/20260119_060000")
        dir.mkdirs()
        java.io.File(dir, "trip.properties").writeText("trip_id=20260119_060000\nstart_ms=1768798800000\npids=0C\n")
        java.io.File(dir, "gps.csv").writeText(
            LiveJournal.GPS_HEADER + "\n1000,1000,37.39,-122.08,40.0,12.5,180,5\n"
        )
        java.io.File(dir, "obd.csv").writeText(
            "ts,gps_time,latitude,longitude,altitude,0C\n1500,1000,37.39,-122.08,40.0,1726\n"
        )

        val store = FileJournalStore(root)
        val trip = store.unfinishedTrips().single()
        assertEquals(1, trip.readGpsRows().size)
        assertEquals(1726.0, trip.readObdRows(trip.pids()).single().values["0C"]!!, 0.001)
        assertFalse(trip.isEmpty())
    }

    @Test
    fun emptyTripDetected() {
        val store = FileJournalStore(tmp.newFolder("journal5"))
        val empty = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        empty.close()
        assertTrue(store.orphanOf(empty).isEmpty())

        val full = store.createTrip("20260119_070000", 1768798800000L, listOf("0C"))
        full.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        full.close()
        assertFalse(store.orphanOf(full).isEmpty())
    }

    @Test
    fun archivedTripsListedAfterRelocation() {
        val store = FileJournalStore(tmp.newFolder("journal8"))
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        journal.close()
        val trip = store.orphanOf(journal)
        store.relocate(trip, toArchive = true)

        assertEquals(listOf("20260119_060000"), store.archivedTrips().map { it.name })
        assertTrue(store.unfinishedTrips().isEmpty())
        // the archived journal stays fully readable (basis of "rebuild from archive")
        assertEquals(1, store.archivedTrips().single().readGpsRows().size)
        assertEquals(1768798800000L, store.archivedTrips().single().startMs())
    }

    @Test
    fun archiveIgnoresDirectoriesWithoutProperties() {
        val root = tmp.newFolder("journal9")
        val store = FileJournalStore(root)
        java.io.File(root, "archive/stray").mkdirs()
        assertTrue(store.archivedTrips().isEmpty())
    }

    @Test
    fun legacySuccessFolderIsRenamedToArchive() {
        val root = tmp.newFolder("journal10")
        val legacy = java.io.File(root, "success/20260119_060000")
        legacy.mkdirs()
        java.io.File(legacy, "trip.properties").writeText("trip_id=20260119_060000\nstart_ms=1768798800000\npids=0C\n")
        java.io.File(legacy, "gps.csv").writeText(LiveJournal.GPS_HEADER + "\n1000,1000,37.39,-122.08,40.0,12.5,180,5\n")

        val store = FileJournalStore(root)
        assertEquals(listOf("20260119_060000"), store.archivedTrips().map { it.name })
        assertTrue(java.io.File(root, "archive/20260119_060000/trip.properties").isFile)
        assertFalse(java.io.File(root, "success").exists())
    }

    @Test
    fun durationSpansFirstToLastSample() {
        val store = FileJournalStore(tmp.newFolder("journal11"))
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        journal.append(ObdRow(31000, null, null, null, null, mapOf("0C" to 900.0)))
        journal.append(GpsRow(61000, 61000, 37.39, -122.08, 40.0, 0f, 180f, 5f))
        journal.close()
        assertEquals(60000L, store.orphanOf(journal).durationMs())
    }

    @Test
    fun durationZeroWithoutSamples() {
        val store = FileJournalStore(tmp.newFolder("journal12"))
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.close()
        assertEquals(0L, store.orphanOf(journal).durationMs())
    }

    @Test
    fun entryCountCountsDataRowsAcrossBothLogs() {
        val store = FileJournalStore(tmp.newFolder("journal14"))
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.close()
        assertEquals(0, store.orphanOf(journal).entryCount()) // headers only

        val j2 = store.createTrip("20260119_070000", 1768798800000L, listOf("0C"))
        j2.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        j2.append(GpsRow(2000, 2000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        j2.append(ObdRow(2000, null, null, null, null, mapOf("0C" to 900.0)))
        j2.close()
        // max(gps, obd) rows: a tick writes both, either may lag while connecting
        assertEquals(2, store.orphanOf(j2).entryCount())
    }

    @Test
    fun archivePolicyRules() {
        val now = 1_700_000_000_000L
        val day = 86_400_000L

        val off = ArchivePolicy(retentionDays = 0, minTripSec = 0)
        assertFalse(off.shouldDelete(now - 365 * day, 1_000, now)) // never delete when both rules are off

        val age = ArchivePolicy(retentionDays = 7, minTripSec = 0)
        assertTrue(age.shouldDelete(now - 8 * day, 10 * 60_000, now))
        assertFalse(age.shouldDelete(now - 6 * day, 10 * 60_000, now))

        val minLen = ArchivePolicy(retentionDays = 0, minTripSec = 60)
        assertTrue(minLen.shouldDelete(now, 59_000, now))
        assertFalse(minLen.shouldDelete(now, 60_000, now))
        assertTrue(minLen.shouldDelete(now, 0, now)) // unknown/empty counts as too short

        val both = ArchivePolicy(retentionDays = 30, minTripSec = 30)
        assertTrue(both.shouldDelete(now - 40 * day, 60_000, now))   // too old
        assertTrue(both.shouldDelete(now, 10_000, now))              // too short
        assertFalse(both.shouldDelete(now - 10 * day, 60_000, now))  // keep
    }

    @Test
    fun rowsRecordTheirUtcOffset() {
        val store = FileJournalStore(tmp.newFolder("journal13"))
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f, tzOffsetMin = -300))
        journal.append(ObdRow(1500, null, null, null, null, mapOf("0C" to 900.0), tzOffsetMin = -300))
        journal.append(GpsRow(2000, 2000, 37.39, -122.08, 40.0, 0f, 0f, 0f)) // auto-fills device offset
        journal.close()

        val trip = store.orphanOf(journal)
        val gps = trip.readGpsRows()
        assertEquals(-300, gps[0].tzOffsetMin!!)
        assertEquals(TripFormats.deviceOffsetMin(2000), gps[1].tzOffsetMin)
        assertEquals(-300, trip.readObdRows(trip.pids())[0].tzOffsetMin!!)
    }

    @Test
    fun legacyRowsWithoutTzColumnParseAsNull() {
        val root = tmp.newFolder("journal14")
        val dir = java.io.File(root, "pending/20260119_060000")
        dir.mkdirs()
        java.io.File(dir, "trip.properties").writeText("trip_id=20260119_060000\nstart_ms=1768798800000\npids=0C\n")
        java.io.File(dir, "gps-000.csv").writeText(LiveJournal.GPS_HEADER + "\n1000,1000,37.39,-122.08,40.0,12.5,180,5\n")
        java.io.File(dir, "obd-000.csv").writeText("ts,gps_time,latitude,longitude,altitude,0C,tz_offset_min\n1500,1000,37.39,-122.08,40.0,1726\n")

        val store = FileJournalStore(root)
        val trip = store.unfinishedTrips().single()
        assertNull(trip.readGpsRows().single().tzOffsetMin)
        assertNull(trip.readObdRows(trip.pids()).single().tzOffsetMin)
        assertNull(trip.tzId())
        assertNull(trip.tzOffsetMin())
    }

    @Test
    fun tripPropertiesRecordZoneAtCreation() {
        val store = FileJournalStore(tmp.newFolder("journal15"))
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.close()
        val trip = store.orphanOf(journal)
        assertEquals(TripFormats.deviceZoneId(), trip.tzId())
        assertEquals(TripFormats.deviceOffsetMin(1768798800000L), trip.tzOffsetMin())
    }
}
