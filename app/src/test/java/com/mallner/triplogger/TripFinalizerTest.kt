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
    fun persistsMultiSourceDistances() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_121000", day1Noon + 600_000, listOf("0D"))
        repeat(10) { i ->
            journal.append(GpsRow(day1Noon + 600_000 + i * 1000, day1Noon + 600_000 + i * 1000, 37.39 + i * 0.001, -122.08, 40.0, 12.5f, 180f, 5f))
        }
        repeat(10) { i ->
            journal.append(ObdRow(day1Noon + 600_000 + i * 1000, null, null, null, null, mapOf("0D" to 45.0)))
        }
        journal.appendOdometer(day1Noon + 600_000, 57215.8)
        journal.appendOdometer(day1Noon + 615_000, 57215.9) // 100 m, in line with the ~112 m integrals
        journal.close()

        val trip = store.orphanOf(journal)
        val result = TripFinalizer.finalize(trip, FinalizeSettings(listOf("0D"), false, false), null, null, FakePlotRenderer)
        assertTrue(result is FinalizeResult.Completed)

        val props = trip.readProps()
        assertEquals("1", props["distance_algo"])
        assertNotNull(props["distance_track_m"])
        assertNotNull(props["distance_gps_speed_m"])
        assertNotNull(props["distance_obd_m"])
        assertEquals("100.0", props["distance_odo_m"])
        assertEquals(DistanceEstimators.SOURCE_ODO, props["distance_src"])
        assertEquals("57215.8", props["odo_start_km"])
        assertEquals("57215.9", props["odo_end_km"])

        // The Trips row model exposes best + all sources from props alone.
        assertEquals(4, trip.distanceEstimates().size)
        assertEquals(DistanceEstimators.SOURCE_ODO, trip.distanceSrc())
        assertEquals(100.0, trip.distanceEstimates()[DistanceEstimators.SOURCE_ODO]!!, 0.05)
    }

    @Test
    fun implausibleOdometerIsRejectedAndNoted() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_122000", day1Noon + 1_200_000, listOf("0D"))
        repeat(10) { i ->
            journal.append(GpsRow(day1Noon + 1_200_000 + i * 1000, day1Noon + 1_200_000 + i * 1000, 37.39 + i * 0.001, -122.08, 40.0, 12.5f, 180f, 5f))
        }
        journal.appendOdometer(day1Noon + 1_200_000, 100.0)
        journal.appendOdometer(day1Noon + 1_815_000, 120.0) // 20 km over a ~1 km trip
        journal.close()

        val trip = store.orphanOf(journal)
        TripFinalizer.finalize(trip, FinalizeSettings(listOf("0D"), false, false), null, null, FakePlotRenderer)

        val props = trip.readProps()
        org.junit.Assert.assertNull(props["distance_odo_m"])
        assertTrue(props["distance_odo_note"]!!.contains("jumped"))
        org.junit.Assert.assertNotEquals(DistanceEstimators.SOURCE_ODO, props["distance_src"])
    }

    /** Captures every [TripMetadataWriter] call the finalizer makes. */
    private class FakeMeta : TripMetadataWriter {
        var conversion: List<Any?>? = null
        val metrics = mutableListOf<List<Any?>>()
        val ext = mutableMapOf<String, String?>()
        val outputs = mutableSetOf<Pair<String, String>>()
        var odoReadings: List<Pair<Long, Double>>? = null

        override fun recordConversion(
            tripId: String, endMs: Long, entries: Int, segments: Int,
            distanceSource: String?, convertedAt: Long, appVersion: String?,
            rawBytes: Long?, rawFileCount: Int?
        ) {
            conversion = listOf(tripId, endMs, entries, segments, distanceSource, convertedAt, appVersion, rawBytes, rawFileCount)
        }

        override fun putMetric(tripId: String, kind: String, source: String, value: Double, note: String?) {
            metrics += listOf(tripId, kind, source, value, note)
        }

        override fun setExt(tripId: String, key: String, value: String?) {
            ext[key] = value
        }

        override fun rememberOutput(tripId: String, kind: String, name: String, bytes: Long?) {
            outputs += kind to name
        }

        override fun replaceOdoReadings(tripId: String, readings: List<Pair<Long, Double>>) {
            odoReadings = readings
        }
    }

    @Test
    fun metaWriterReceivesAllDerivedData() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_121000", day1Noon + 600_000, listOf("0D"))
        repeat(10) { i ->
            journal.append(GpsRow(day1Noon + 600_000 + i * 1000, day1Noon + 600_000 + i * 1000, 37.39 + i * 0.001, -122.08, 40.0, 12.5f, 180f, 5f))
        }
        repeat(10) { i ->
            journal.append(ObdRow(day1Noon + 600_000 + i * 1000, null, null, null, null, mapOf("0D" to 45.0)))
        }
        journal.appendOdometer(day1Noon + 600_000, 57215.8)
        journal.appendOdometer(day1Noon + 615_000, 57215.9)
        journal.close()

        val trip = store.orphanOf(journal)
        val kmzDir = tmp.newFolder("kmz-meta")
        val meta = FakeMeta()
        val result = TripFinalizer.finalize(
            trip,
            FinalizeSettings(listOf("0D"), generateKmz = true, saveOds = false, appVersionName = "1.3"),
            DirStorage(kmzDir), null, FakePlotRenderer,
            meta = meta,
            target = FinalizeTarget("t${day1Noon + 600_000}", day1Noon + 600_000, null, listOf("0D"), null)
        )
        assertTrue(result is FinalizeResult.Completed)

        val conversion = meta.conversion!!
        assertEquals("t${day1Noon + 600_000}", conversion[0])   // row id, not folder
        assertEquals(1, conversion[3])                          // one gps segment
        assertEquals(DistanceEstimators.SOURCE_ODO, conversion[4])
        assertEquals("1.3", conversion[6])

        val distances = meta.metrics.filter { it[1] == "distance_m" }.associate { it[2] to it[3] }
        assertEquals(
            setOf(
                DistanceEstimators.SOURCE_TRACK, DistanceEstimators.SOURCE_GPS_SPEED,
                DistanceEstimators.SOURCE_OBD, DistanceEstimators.SOURCE_ODO
            ),
            distances.keys
        )
        val odoWindow = meta.metrics.filter { it[1] == "odo_km" }.associate { it[2] to it[3] }
        assertEquals(mapOf("start" to 57215.8, "end" to 57215.9), odoWindow)
        assertEquals(57215.8, (meta.odoReadings?.firstOrNull()?.second ?: 0.0), 1e-9)
        assertEquals(setOf("kmz_day" to "2026-01-19.kmz"), meta.outputs)
        assertEquals("1", meta.ext["distance_algo"])
        org.junit.Assert.assertNull(meta.ext["distance_odo_note"])
        // The row is the record now: no trip.properties is left behind.
        assertTrue(trip.readProps().isEmpty())
    }

    @Test
    fun metaWriterNotesRejectedOdometer() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_122000", day1Noon + 1_200_000, listOf("0D"))
        repeat(10) { i ->
            journal.append(GpsRow(day1Noon + 1_200_000 + i * 1000, day1Noon + 1_200_000 + i * 1000, 37.39 + i * 0.001, -122.08, 40.0, 12.5f, 180f, 5f))
        }
        journal.appendOdometer(day1Noon + 1_200_000, 100.0)
        journal.appendOdometer(day1Noon + 1_815_000, 120.0) // 20 km over a ~1 km trip
        journal.close()

        val meta = FakeMeta()
        TripFinalizer.finalize(
            store.orphanOf(journal),
            FinalizeSettings(listOf("0D"), false, false),
            null, null, FakePlotRenderer,
            meta = meta,
            target = FinalizeTarget("t${day1Noon + 1_200_000}", day1Noon + 1_200_000, null, listOf("0D"), null)
        )
        assertTrue(meta.metrics.none { it[1] == "distance_m" && it[2] == DistanceEstimators.SOURCE_ODO })
        assertTrue((meta.ext["distance_odo_note"] ?: "").contains("jumped"))
        org.junit.Assert.assertNotEquals(DistanceEstimators.SOURCE_ODO, meta.conversion!![4])
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
        var tzOffsetMin: Int? = null
        var tzChanges: List<Pair<Long, Int>> = emptyList()
        var dateChanges: List<Pair<Long, String>> = emptyList()
        var obdSpeeds: List<Pair<Long, Float>> = emptyList()
        var gpsSpeeds: List<Pair<Long, Float>> = emptyList()
        override fun renderPng(label: String, speedUnit: String, tzOffsetMin: Int?, tzChanges: List<Pair<Long, Int>>, dateChanges: List<Pair<Long, String>>, obdSpeeds: List<Pair<Long, Float>>, gpsSpeeds: List<Pair<Long, Float>>): ByteArray {
            this.speedUnit = speedUnit
            this.tzOffsetMin = tzOffsetMin
            this.tzChanges = tzChanges
            this.dateChanges = dateChanges
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
    fun dateChangeIsMarkedAcrossMidnight() {
        val before = 1793940900000L // 2026-11-06 04:55 UTC = local -05:00 on 11-05 23:55
        val after = 1793941500000L  // 05:05 UTC = local -05:00 on 11-06 00:05
        val rows = listOf(
            GpsRow(before, before, 37.39, -122.08, 40.0, 10f, 0f, 5f, tzOffsetMin = -300),
            GpsRow(after, after, 37.39, -122.08, 40.0, 10f, 0f, 5f, tzOffsetMin = -300)
        )
        assertEquals(listOf(Pair(after, "11-06")), TripFinalizer.dateChanges(rows, emptyList(), -300))
    }

    @Test
    fun dateChangeFromZoneJumpAlone() {
        val utcBefore = 1793901600000L // 2026-11-05 18:00 UTC: day 11-05 at +00:00, 11-06 02:00 at +08:00
        val rows = listOf(
            GpsRow(utcBefore, utcBefore, 37.39, -122.08, 40.0, 10f, 0f, 5f, tzOffsetMin = 0),
            GpsRow(utcBefore + 60_000, utcBefore + 60_000, 37.39, -122.08, 40.0, 10f, 0f, 5f, tzOffsetMin = 8 * 60)
        )
        // crossing into UTC+8 rolls the wall clock to the next day without midnight UTC
        val changes = TripFinalizer.dateChanges(rows, emptyList(), 0)
        assertEquals(1, changes.size)
        assertEquals("11-06", changes.single().second)
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

    @Test
    fun dstChangeIsMarkedForThePlot() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_120000", day1Noon, listOf("0C"))
        repeat(5) { i ->
            journal.append(GpsRow(day1Noon + i * 1000L, day1Noon + i * 1000L, 37.39, -122.08, 40.0, 10f, 0f, 5f, tzOffsetMin = -300))
        }
        repeat(5) { i ->
            journal.append(GpsRow(day1Noon + (5 + i) * 1000L, day1Noon + (5 + i) * 1000L, 37.39, -122.08, 40.0, 10f, 0f, 5f, tzOffsetMin = -240))
        }
        journal.close()

        val renderer = CaptureRenderer()
        val result = TripFinalizer.finalize(
            store.orphanOf(journal),
            FinalizeSettings(listOf("0C"), generateKmz = true, saveOds = false),
            DirStorage(tmp.newFolder("kmz")), null, renderer
        )
        assertTrue(result is FinalizeResult.Completed)
        assertEquals(-300, renderer.tzOffsetMin!!)              // axis uses the start-of-trip zone
        assertEquals(Pair(day1Noon + 5000L, -240), renderer.tzChanges.single())
        assertEquals(emptyList<Pair<Long, String>>(), renderer.dateChanges) // noon -> noon, no day flip
    }

    @Test
    fun odsTimesIncludeTheOffset() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val odsDir = tmp.newFolder("ods")
        val journal = store.createTrip("20260119_120000", day1Noon, listOf("0C"))
        journal.append(GpsRow(day1Noon, day1Noon, 37.39, -122.08, 40.0, 10f, 0f, 5f, tzOffsetMin = -300))
        journal.append(ObdRow(day1Noon, day1Noon, 37.39, -122.08, 40.0, mapOf("0C" to 800.0), tzOffsetMin = -300))
        journal.close()
        val result = TripFinalizer.finalize(
            store.orphanOf(journal),
            FinalizeSettings(listOf("0C"), generateKmz = false, saveOds = true),
            null, DirStorage(odsDir), FakePlotRenderer
        )
        assertTrue(result is FinalizeResult.Completed)

        val ods = odsDir.listFiles()!!.first()
        var content = ""
        java.util.zip.ZipInputStream(java.io.FileInputStream(ods)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith("content.xml")) content = z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        assertTrue("ods time lacks the recorded offset: $content", content.contains("-05:00</text:p>"))
    }

    @Test
    fun gpslessTripExportsOdsButNoKmz() {
        // Rebuild can encounter archived OBD-only trips (GPS logging off when
        // recorded). They must not create an empty KMZ track with a plot.
        val store = FileJournalStore(tmp.newFolder("journal"))
        val kmzDir = tmp.newFolder("kmz")
        val odsDir = tmp.newFolder("ods")
        val journal = store.createTrip("20260119_120000", day1Noon, listOf("0C", "0D"))
        repeat(15) { i ->
            journal.append(
                ObdRow(
                    day1Noon + i * 1000, null, Double.NaN, Double.NaN, Double.NaN,
                    mapOf("0C" to (900 + i).toDouble(), "0D" to (30 + i).toDouble())
                )
            )
        }
        journal.close()
        val result = TripFinalizer.finalize(
            store.orphanOf(journal),
            FinalizeSettings(listOf("0C", "0D"), generateKmz = true, saveOds = true),
            DirStorage(kmzDir), DirStorage(odsDir), FakePlotRenderer
        )
        assertTrue(result is FinalizeResult.Completed)
        org.junit.Assert.assertFalse("empty kmz was written", java.io.File(kmzDir, "2026-01-19.kmz").exists())
        assertTrue("ods missing for OBD-only trip", odsDir.listFiles()!!.isNotEmpty())
    }
}
