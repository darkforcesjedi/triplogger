package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        store.relocate(trips.first(), toSuccess = false)

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
        store.relocate(trip, toSuccess = true)

        assertFalse(java.io.File(root, "pending/20260119_060000").exists())
        assertTrue(java.io.File(root, "success/20260119_060000/gps.csv").isFile)
        assertTrue(java.io.File(root, "success/20260119_060000/obd.csv").isFile)
    }

    @Test
    fun truncatedLinesAreIgnored() {
        val root = tmp.newFolder("journal4")
        val store = FileJournalStore(root)
        val journal = store.createTrip("20260119_060000", 1768798800000L, listOf("0C"))
        journal.append(GpsRow(1000, 1000, 37.39, -122.08, 40.0, 12.5f, 180f, 5f))
        journal.close()
        // simulate a kill mid-write by corrupting the last line
        java.io.File(root, "pending/20260119_060000/gps.csv").appendText("1500,1500,37.39\n")

        val trip = store.orphanOf(journal)
        assertEquals(1, trip.readGpsRows().size)
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
}
