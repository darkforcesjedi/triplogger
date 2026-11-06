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
        store.relocate(trip, toArchive = result is FinalizeResult.Completed)

        assertTrue(java.io.File(journalRoot, "archive/20260119_120000/gps-000.csv").isFile)
        assertEquals(0, store.unfinishedTrips().size)
    }

    private class CaptureRenderer : PlotRenderer {
        var speedUnit: String = ""
        var obdSpeeds: List<Pair<Long, Float>> = emptyList()
        var gpsSpeeds: List<Pair<Long, Float>> = emptyList()
        override fun renderPng(label: String, speedUnit: String, obdSpeeds: List<Pair<Long, Float>>, gpsSpeeds: List<Pair<Long, Float>>): ByteArray {
            this.speedUnit = speedUnit
            this.obdSpeeds = obdSpeeds
            this.gpsSpeeds = gpsSpeeds
            return "PNG:$label".toByteArray()
        }
    }

    private fun tripWithSelectedPidOrder(store: JournalStore, selected: List<String>): Pair<LiveJournal, FinalizeSettings> {
        val journal = store.createTrip("20260119_120000", day1Noon, listOf("0C", "0D"))
        journal.append(GpsRow(day1Noon, day1Noon, 37.39, -122.08, 40.0, 10f, 0f, 5f))
        journal.append(ObdRow(day1Noon, day1Noon, 37.39, -122.08, 40.0, mapOf("0C" to 1500.0, "0D" to 45.0)))
        journal.close()
        return Pair(journal, FinalizeSettings(selected, generateKmz = true, saveOds = false))
    }

    @Test
    fun journalIsParsedWithItsOwnPidOrder() {
        // OBD rows are positional: even if the settings now list the PIDs swapped,
        // the trip must be read back with the list it recorded (0C=1500, 0D=45).
        val store = FileJournalStore(tmp.newFolder("journal"))
        val (journal, settings) = tripWithSelectedPidOrder(store, listOf("0D", "0C"))
        val renderer = CaptureRenderer()
        val result = TripFinalizer.finalize(store.orphanOf(journal), settings, DirStorage(tmp.newFolder("kmz")), null, renderer)
        assertTrue(result is FinalizeResult.Completed)
        // 45 km/h -> 27.96 mph (a positional mix-up would show ~932 mph from the RPM)
        assertEquals(27.96, renderer.obdSpeeds.single().second.toDouble(), 0.05)
    }

    @Test
    fun exportHonoursSelectedUnits() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val (journal, us) = tripWithSelectedPidOrder(store, listOf("0C", "0D"))
        val usRenderer = CaptureRenderer()
        TripFinalizer.finalize(store.orphanOf(journal), us, DirStorage(tmp.newFolder("kmz1")), null, usRenderer)
        assertEquals("mph", usRenderer.speedUnit)
        assertEquals(22.37, usRenderer.gpsSpeeds.single().second.toDouble(), 0.02) // 10 m/s

        val (journal2, _) = tripWithSelectedPidOrder(store, listOf("0C", "0D"))
        val metricRenderer = CaptureRenderer()
        TripFinalizer.finalize(
            store.orphanOf(journal2),
            FinalizeSettings(listOf("0C", "0D"), generateKmz = true, saveOds = false, usUnits = false),
            DirStorage(tmp.newFolder("kmz2")), null, metricRenderer
        )
        assertEquals("km/h", metricRenderer.speedUnit)
        assertEquals(45.0, metricRenderer.obdSpeeds.single().second.toDouble(), 0.001)
        assertEquals(36.0, metricRenderer.gpsSpeeds.single().second.toDouble(), 0.001) // 10 m/s
    }
}
