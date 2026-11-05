package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripFinalizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Noon on 2026-01-19 local time, so day-derived file names are TZ-proof
    private val day1Noon: Long = java.time.ZonedDateTime.of(2026, 1, 19, 12, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun makeTrip(store: JournalStore, tripId: String, start: Long): LiveJournal {
        val journal = store.createTrip(tripId, start, listOf("0C", "0D"))
        repeat(10) { i ->
            journal.append(GpsRow(start + i * 1000, start + i * 1000, 37.39 + i * 0.001, -122.08 - i * 0.001, 40.0, 12.5f, 180f, 5f))
        }
        repeat(20) { i ->
            journal.append(
                ObdRow(
                    start + i * 500, start + i * 500, 37.39, -122.08, 40.0,
                    mapOf("0C" to (1500 + i).toDouble(), "0D" to (40 + i).toDouble())
                )
            )
        }
        journal.close()
        return journal
    }

    @Test
    fun writesAllArtifacts() {
        val journalRoot = tmp.newFolder("journal")
        val store = FileJournalStore(journalRoot)
        val kmzDir = tmp.newFolder("kmz")
        val odsDir = tmp.newFolder("ods")

        val journal = makeTrip(store, "20260119_120000", day1Noon)
        val trip = store.orphanOf(journal)
        val result = TripFinalizer.finalize(
            trip,
            FinalizeSettings(listOf("0C", "0D"), generateKmz = true, saveOds = true),
            DirStorage(kmzDir),
            DirStorage(odsDir),
            FakePlotRenderer
        )
        assertTrue(result is FinalizeResult.Completed)

        val kmzFile = java.io.File(kmzDir, "2026-01-19.kmz")
        assertTrue("kmz missing", kmzFile.isFile)
        val (kml, ids) = DailyKmz.inspect(kmzFile.readBytes())
        assertNotNull(kml)
        assertEquals(1, ids.size)

        assertTrue(java.io.File(odsDir, "engine-2026-01-19 12.00.00.ods").isFile)
    }

    @Test
    fun emptyTripIsSkipped() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val empty = store.createTrip("20260119_120000", day1Noon, listOf("0C"))
        empty.close()
        val result = TripFinalizer.finalize(
            store.orphanOf(empty),
            FinalizeSettings(listOf("0C"), generateKmz = true, saveOds = false),
            DirStorage(tmp.newFolder("kmz")),
            null,
            FakePlotRenderer
        )
        assertTrue(result is FinalizeResult.Skipped)
    }

    @Test
    fun crashBetweenTripsStillProducesBothTracks() {
        // Trip 1 completes and writes into the daily KMZ; simulate crash (trip 2
        // never finalized) and then recovery merging trip 2 into the same day.
        val journalRoot = tmp.newFolder("journal")
        val store = FileJournalStore(journalRoot)
        val kmzDir = tmp.newFolder("kmz")

        val journal1 = makeTrip(store, "20260119_120000", day1Noon)
        TripFinalizer.finalize(
            store.orphanOf(journal1),
            FinalizeSettings(listOf("0C", "0D"), true, false),
            DirStorage(kmzDir), null, FakePlotRenderer
        )

        val start2 = day1Noon + 3 * 3600_000L // same day, later trip
        val journal2 = makeTrip(store, "20260119_150000", start2)
        TripFinalizer.finalize(
            store.orphanOf(journal2),
            FinalizeSettings(listOf("0C", "0D"), true, false),
            DirStorage(kmzDir), null, FakePlotRenderer
        )

        val kmz = java.io.File(kmzDir, "2026-01-19.kmz").readBytes()
        val (_, ids) = DailyKmz.inspect(kmz)
        assertEquals(2, ids.size)
    }

    @Test
    fun relocateAfterConversionResult() {
        val journalRoot = tmp.newFolder("journal")
        val store = FileJournalStore(journalRoot)
        val journal = makeTrip(store, "20260119_120000", day1Noon)
        val trip = store.orphanOf(journal)

        val result = TripFinalizer.finalize(trip, FinalizeSettings(listOf("0C", "0D"), true, false), DirStorage(tmp.newFolder("kmz")), null, FakePlotRenderer)
        store.relocate(trip, toSuccess = result is FinalizeResult.Completed)

        assertTrue(java.io.File(journalRoot, "success/20260119_120000/gps.csv").isFile)
        assertEquals(0, store.unfinishedTrips().size)
    }
}
