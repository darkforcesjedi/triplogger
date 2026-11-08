package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Trips-tab foundations: metadata write path, finalize-derived stats, KMZ
 * placemark renames (title / id migration) and Nominatim response parsing.
 */
class TripsMetadataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day1Noon: Long = java.time.ZonedDateTime.of(2026, 1, 19, 12, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ------------------------------------------------------------ props merge

    @Test
    fun mergePropsRoundTripAndSanitizing() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_120000", day1Noon, listOf("0C"), "Ford Escape")
        journal.close()
        val trip = store.orphanOf(journal)

        assertEquals("Ford Escape", trip.trigger())

        trip.mergeProps(mapOf("title" to "Run to  Mall", "distance_m" to "1234.5"))
        assertEquals("Run to  Mall", trip.title())
        assertEquals(1234.5, trip.distanceM(), 0.01)

        trip.mergeProps(mapOf("title" to "Multi\nline\"x\""))
        assertEquals("Multi line\"x\"", trip.title()) // newlines flattened

        trip.mergeProps(mapOf("title" to null))
        assertNull(trip.title()) // null removes the key
    }

    // --------------------------------------------------- finalize metadata

    @Test
    fun finalizeWritesDerivedMetadata() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_120000", day1Noon, listOf("0C", "0D"), "manual")
        repeat(10) { i ->
            journal.append(GpsRow(day1Noon + i * 1000, day1Noon + i * 1000, 37.39 + i * 0.001, -122.08, 40.0, 12.5f, 180f, 5f))
        }
        journal.append(ObdRow(day1Noon, day1Noon, 37.39, -122.08, 40.0, mapOf("0C" to 1500.0, "0D" to 40.0)))
        journal.close()
        val trip = store.orphanOf(journal)

        val kmzDir = tmp.newFolder("kmz")
        val result = TripFinalizer.finalize(
            trip,
            FinalizeSettings(listOf("0C", "0D"), generateKmz = true, saveOds = true, appVersionName = "9.9-test"),
            DirStorage(kmzDir),
            DirStorage(tmp.newFolder("ods")),
            FakePlotRenderer
        )
        assertTrue(result is FinalizeResult.Completed)

        assertTrue(trip.isConverted())
        assertTrue(trip.endMs() > trip.startMs())
        assertEquals("9.9-test", trip.appVersionName())
        assertEquals(10, trip.readProps()["entries"]?.toInt())
        assertTrue("distance stored", trip.distanceM() > 100)
        assertEquals(12.5f, trip.maxSpeedMps(), 0.01f)
        assertEquals("2026-01-19", trip.kmzDay())
        assertEquals("engine-2026-01-19 12.00.00.ods", trip.odsFile())

        // epoch-based track id, not the wall-clock label
        val (kml, ids) = DailyKmz.inspect(java.io.File(kmzDir, "2026-01-19.kmz").readBytes())
        assertTrue("track id should be t<epochms>, was $ids", ids.single() == "t$day1Noon")
        assertNotNull(kml)
    }

    @Test
    fun distanceIsHaversinePath() {
        val meterPerDegLat = 111_195.0
        val rows = listOf(
            GpsRow(1, 1, 37.0, -122.0, 0.0, 0f, 0f, 0f),
            GpsRow(2, 2, 37.01, -122.0, 0.0, 0f, 0f, 0f) // 0.01 deg north
        )
        val d = TripFinalizer.tripDistanceMeters(rows)
        assertTrue("got $d", d in (meterPerDegLat * 0.009)..(meterPerDegLat * 0.011))
    }

    @Test
    fun stationaryJitterIsNotDistance() {
        val rows = (0..50).map { GpsRow(it.toLong(), it.toLong(), 37.39 + it * 1e-7, -122.08, 0.0, 0f, 0f, 0f) }
        assertEquals(0.0, TripFinalizer.tripDistanceMeters(rows), 0.01)
    }

    // ------------------------------------------------------------ KMZ rename

    private data class Fixture(val trip: OrphanTrip, val kmzFile: java.io.File)

    private fun convertedTrip(): Fixture {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20260119_120000", day1Noon, listOf("0C", "0D"))
        repeat(5) { i ->
            journal.append(GpsRow(day1Noon + i * 1000, day1Noon + i * 1000, 37.39 + i * 0.001, -122.08, 40.0, 12.5f, 180f, 5f))
        }
        journal.close()
        val trip = store.orphanOf(journal)
        val kmzDir = tmp.newFolder("kmz")
        val result = TripFinalizer.finalize(
            trip,
            FinalizeSettings(listOf("0C", "0D"), generateKmz = true, saveOds = false),
            DirStorage(kmzDir),
            null,
            FakePlotRenderer
        )
        assertTrue(result is FinalizeResult.Completed)
        return Fixture(trip, java.io.File(kmzDir, "2026-01-19.kmz"))
    }

    @Test
    fun renameSetsNameAndCaptionAndMigratesLegacyId() {
        val (trip, kmzFile) = convertedTrip()
        val bytes = kmzFile.readBytes()

        // simulate a v1.1 file: legacy label-derived id
        val legacyId = trip.legacyTrackId()
        val (kml0, ids0) = DailyKmz.inspect(bytes)
        assertTrue(ids0.single() == trip.trackId())
        val legacyKmz = repackKml(bytes, kml0!!.replace("gx:Track id=\"${trip.trackId()}\"", "gx:Track id=\"$legacyId\""))

        val edited = DailyKmz.renamePlacemark(
            legacyKmz,
            listOf(trip.trackId(), legacyId),
            "Run to Perimeter Mall & Co"
        )
        assertNotNull(edited)
        val (kml, ids) = DailyKmz.inspect(edited!!)
        // id migrated to the epoch-based form
        assertEquals(setOf(trip.trackId()), ids)
        assertTrue("name not set: $kml", kml!!.contains("<name>Run to Perimeter Mall &amp; Co</name>"))
        assertTrue("caption title missing", kml.contains("Run to Perimeter Mall &amp; Co<br/>"))
        assertTrue("timestamp kept", kml.contains("<when>"))
    }

    @Test
    fun renameWithNullRestoresLabelName() {
        val (trip, kmzFile) = convertedTrip()
        val renamed = DailyKmz.renamePlacemark(kmzFile.readBytes(), listOf(trip.trackId()), "Something")!!
        val cleared = DailyKmz.renamePlacemark(renamed, listOf(trip.trackId()), null)!!
        val (kml, ids) = DailyKmz.inspect(cleared)
        assertEquals(setOf(trip.trackId()), ids)
        assertTrue("label name restored: $kml", kml!!.contains("<name>2026-01-19 12.00.00"))
        assertTrue("no leftover title", !kml.contains("Something"))
    }

    @Test
    fun renameMissingIdReturnsNull() {
        val (_, kmzFile) = convertedTrip()
        assertNull(DailyKmz.renamePlacemark(kmzFile.readBytes(), listOf("t1234", "nope"), "X"))
    }

    private fun repackKml(kmzBytes: ByteArray, kml: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(kmzBytes)).use { zip ->
            java.util.zip.ZipOutputStream(out).use { outZip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    outZip.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    if (entry.name.endsWith(".kml")) {
                        outZip.write(kml.toByteArray(Charsets.UTF_8))
                    } else {
                        zip.copyTo(outZip)
                    }
                    outZip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    // --------------------------------------------------------- Nominatim JSON

    @Test
    fun nominatimParsesTimezoneAndTidiedName() {
        val json = """
            {"display_name":"Atlanta, Fulton County, GA, USA",
             "timezone":"America/New_York",
             "address":{"city":"Atlanta","state":"Georgia","postcode":"30303","country":"USA"}}
        """.trimIndent()
        val place = NominatimClient.parse(json)
        assertNotNull(place)
        assertEquals("America/New_York", place!!.timezone)
        assertEquals("Atlanta, Georgia, 30303", place.name)
    }

    @Test
    fun nominatimFallsBackToDisplayNameWithoutAddress() {
        val json = """{"display_name":"Somewhere, Over the Rainbow","timezone":"Etc/UTC"}"""
        val place = NominatimClient.parse(json)!!
        assertEquals("Etc/UTC", place.timezone)
        // without address parts the display name is kept whole
        assertEquals("Somewhere, Over the Rainbow", place.name)
    }

    @Test
    fun nominatimGarbageIsNull() {
        assertNull(NominatimClient.parse("not json"))
        assertNull(NominatimClient.parse("{}"))
    }
}
