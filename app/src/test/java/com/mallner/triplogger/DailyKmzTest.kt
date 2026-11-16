package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

object FakePlotRenderer : PlotRenderer {
    override fun renderPng(label: String, speedUnit: String, tzOffsetMin: Int?, tzChanges: List<Pair<Long, Int>>, dateChanges: List<Pair<Long, String>>, obdSpeeds: List<Pair<Long, Float>>, gpsSpeeds: List<Pair<Long, Float>>): ByteArray =
        "PNG:$label".toByteArray()
}

class DailyKmzTest {

    private fun trip(id: String, points: Int = 3, baseMs: Long = 1768797600000L): TripTrack {
        val base = baseMs // 2026-01-19 06:00 local-ish
        val pts = (0 until points).map { i ->
            TrackPoint(base + i * 1000L, -122.0834 - i * 0.001, 37.389 + i * 0.001, 10.0 + i)
        }
        return TripTrack(
            trackId = id,
            label = id.replace(':', '.'),
            speedUnit = "mph",
            points = pts,
            obdSpeeds = listOf(Pair(base, 55f), Pair(base + 5000, 60f)),
            gpsSpeeds = listOf(Pair(base, 54f), Pair(base + 5000, 59f))
        )
    }

    @Test
    fun mergesFreshTripIntoNewDailyFile() {
        val kmz = DailyKmz(renderer = FakePlotRenderer)
            .merge(null, "2026-01-19", listOf(trip("2026-01-19 06:00:00")))
        val (kml, ids) = DailyKmz.inspect(kmz)
        assertNotNull(kml)
        assertTrue("2026-01-19 06:00:00" in ids)
        assertTrue(kml!!.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(kml.contains("<gx:Track id=\"2026-01-19 06:00:00\">"))
        assertTrue(kml.contains("<color>FF1400FF</color>")) // first color in cycle
        // PNG for the trip is embedded
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(kmz)).use { z ->
            var e = z.nextEntry
            while (e != null) { names.add(e.name); e = z.nextEntry }
        }
        assertTrue(names.any { it == "2026-01-19.kml" })
        assertTrue(names.any { it.endsWith(".png") })
    }

    @Test
    fun mergingTwiceWithSameTrackIdIsIdempotent() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        val first = builder.merge(null, "2026-01-19", listOf(trip("2026-01-19 06:00:00")))
        val second = builder.merge(first, "2026-01-19", listOf(trip("2026-01-19 06:00:00")))
        val (kml, ids) = DailyKmz.inspect(second)
        assertEquals(1, ids.size)
        assertEquals(1, Regex("<Placemark>").findAll(kml!!).count())
    }

    @Test
    fun secondTripGetsNextColorAndBothTracksSurvive() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        var kmz: ByteArray? = null
        kmz = builder.merge(kmz, "2026-01-19", listOf(trip("2026-01-19 06:00:00")))
        kmz = builder.merge(kmz, "2026-01-19", listOf(trip("2026-01-19 07:30:00")))
        val (kml, ids) = DailyKmz.inspect(kmz)
        assertEquals(2, ids.size)
        val colors = Regex("<color>(\\w+)</color>").findAll(kml!!).map { it.groupValues[1] }.toList()
        assertEquals(listOf("FF1400FF", "FF14F0FF"), colors)
    }

    @Test
    fun existingEntriesArePreservedOnMerge() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        val first = builder.merge(null, "2026-01-19", listOf(trip("2026-01-19 06:00:00")))
        val pngNamesBefore = ArrayList<String>().apply {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(first)).use { z ->
                var e = z.nextEntry
                while (e != null) { if (e.name.endsWith(".png")) add(e.name); e = z.nextEntry }
            }
        }
        val second = builder.merge(first, "2026-01-19", listOf(trip("2026-01-19 07:30:00")))
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(second)).use { z ->
            val seen = HashMap<String, ByteArray>()
            var e = z.nextEntry
            while (e != null) { seen[e.name] = z.readBytes(); e = z.nextEntry }
            pngNamesBefore.forEach { assertTrue("missing $it", seen.containsKey(it)) }
        }
    }

    @Test
    fun reprocessedTripReplacesItsPlacemark() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        val first = builder.merge(null, "2026-01-19", listOf(trip("2026-01-19 06:00:00", points = 3)))
        val rebuilt = builder.merge(first, "2026-01-19", listOf(trip("2026-01-19 06:00:00", points = 7)))
        val (kml, ids) = DailyKmz.inspect(rebuilt)
        assertEquals(setOf("2026-01-19 06:00:00"), ids)
        assertEquals(1, Regex("<Placemark>").findAll(kml!!).count())
        // the updated data won: 7 coordinates, the 3 old ones are gone
        assertEquals(7, Regex("<gx:coord>").findAll(kml).count())
    }

    @Test
    fun replacementKeepsColorAndOtherTrips() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        val t1 = trip("2026-01-19 06:00:00")
        var kmz: ByteArray? = null
        kmz = builder.merge(kmz, "2026-01-19", listOf(t1))
        kmz = builder.merge(kmz, "2026-01-19", listOf(trip("2026-01-19 07:30:00")))
        val grown = t1.copy(points = t1.points + t1.points.last())
        kmz = builder.merge(kmz!!, "2026-01-19", listOf(grown))
        val (kml, ids) = DailyKmz.inspect(kmz)
        assertEquals(2, ids.size)
        assertEquals(2, Regex("<Placemark>").findAll(kml!!).count())
        val colors = Regex("<color>(\\w+)</color>").findAll(kml).map { it.groupValues[1] }.toList()
        assertEquals(listOf("FF1400FF", "FF14F0FF"), colors) // original colors, not a new cycle
        assertEquals(4 + 3, Regex("<gx:coord>").findAll(kml).count()) // 3+1 from t1, 3 from t2
    }

    @Test
    fun whenElementsCarryTheRecordedOffset() {
        val base = 1768797600000L
        val tzTrip = TripTrack(
            trackId = "2026-01-19 06:00:00",
            label = "2026-01-19 06.00.00",
            speedUnit = "mph",
            points = listOf(TrackPoint(base, -122.0834, 37.389, 10.0, tzOffsetMin = -300)),
            obdSpeeds = emptyList(),
            gpsSpeeds = emptyList()
        )
        val (kml, _) = DailyKmz.inspect(
            DailyKmz(renderer = FakePlotRenderer).merge(null, "2026-01-19", listOf(tzTrip))
        )
        assertTrue("when lacks offset: $kml", kml!!.contains("-05:00</when>"))
        // the placemark name and image caption also carry the start-of-trip offset
        assertTrue("name lacks UTC offset: $kml", kml.contains("<name>2026-01-19 06.00.00 UTC-05:00</name>"))
        assertTrue("caption lacks offset: $kml", kml.contains("<img"))
        assertTrue("caption when lacks offset", kml.contains("-05:00<br/><img"))
    }

    @Test
    fun placemarksAreOrderedByActualUtcTimeAcrossAnOffsetChange() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        // A trip recorded earlier in UTC but in a +07:00 zone (so a later wall clock)
        // must sort before one 6h later in UTC that is in a -06:00 zone.
        val earlierUtc = TripTrack(
            trackId = "a", label = "a", speedUnit = "mph",
            points = listOf(TrackPoint(1_000_000L, 1.0, 1.0, 0.0, tzOffsetMin = 420)),
            obdSpeeds = listOf(Pair(1_000_000L, 1f)), gpsSpeeds = emptyList()
        )
        val laterUtc = TripTrack(
            trackId = "b", label = "b", speedUnit = "mph",
            points = listOf(TrackPoint(1_000_000L + 6 * 3_600_000L, 2.0, 2.0, 0.0, tzOffsetMin = -360)),
            obdSpeeds = listOf(Pair(2_000_000L, 1f)), gpsSpeeds = emptyList()
        )
        // merge in reverse order; the output must still list "a" before "b"
        val (kml, _) = DailyKmz.inspect(builder.merge(null, "d", listOf(laterUtc, earlierUtc)))
        assertTrue(kml!!.indexOf("<gx:Track id=\"a\">") < kml.indexOf("<gx:Track id=\"b\">"))
    }

    // ---- stop pins ----------------------------------------------------------

    private val pinStop = StopEvent(1768797660_000L, 1768797960_000L, 37.389, -122.0834, "wheel")

    @Test
    fun locatedStopsBecomeDurationPinsAndUnlocatedOnesAreSkipped() {
        val t = trip("t1768797600000").copy(stops = listOf(
            pinStop,
            StopEvent(1768798500_000L, 1768798800_000L, null, null, "gps")
        ))
        val (kml, _) = DailyKmz.inspect(
            DailyKmz(renderer = FakePlotRenderer).merge(null, "2026-01-19", listOf(t))
        )
        assertTrue("missing pin placemark: $kml",
            kml!!.contains("<Placemark id=\"t1768797600000-stop${pinStop.startMs}\">"))
        assertEquals(1, Regex("<Placemark id=").findAll(kml).count()) // no fix -> no pin
        assertTrue("stop trips must nest under a folder", kml.contains("<Folder>"))
        assertTrue("folder lacks trip name", kml.contains("<Folder>\n  <name>t1768797600000</name>"))
        assertTrue("name lacks duration", kml.contains("<name>Stopped for 5:00</name>"))
        assertTrue("balloon lacks arrival", kml.contains("Arrive: "))
        assertTrue("balloon lacks departure", kml.contains("Depart: "))
        assertFalse("balloon still names the source", kml.contains("wheel"))
        assertTrue("missing point coords", kml.contains("<coordinates>-122.0834,37.389,0</coordinates>"))
        assertTrue("pin must carry a TimeStamp for ordering", kml.contains("<TimeStamp><when>"))
    }

    @Test
    fun pinsAreReplacedNotDuplicatedOnRebuild() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        val t = trip("t1768797600000").copy(stops = listOf(pinStop))
        var kmz = builder.merge(null, "2026-01-19", listOf(t))
        kmz = builder.merge(kmz, "2026-01-19", listOf(t))
        var kml = DailyKmz.inspect(kmz).first
        assertEquals(1, Regex("<Placemark id=").findAll(kml!!).count())
        assertEquals(1, Regex("<Folder>").findAll(kml).count())
        // A rebuild with a different stop list must drop the stale pin.
        val changed = t.copy(stops = listOf(StopEvent(1768798200_000L, 1768798500_000L, 37.39, -122.08, "gps")))
        kmz = builder.merge(kmz, "2026-01-19", listOf(changed))
        kml = DailyKmz.inspect(kmz).first
        assertEquals(1, Regex("<Placemark id=").findAll(kml!!).count())
        assertEquals(1, Regex("<Folder>").findAll(kml).count())
        assertTrue(kml.contains("-stop1768798200000"))
        assertFalse("stale pin survived rebuild", kml.contains("-stop${pinStop.startMs}"))
    }

    @Test
    fun stopFreeTripsStayFlatBesideFolders() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        var kmz = builder.merge(null, "2026-01-19", listOf(trip("t1768797600000").copy(stops = listOf(pinStop))))
        kmz = builder.merge(kmz, "2026-01-19", listOf(trip("t1768800000000")))
        // And the folder of the first trip must survive re-splitting the file.
        kmz = builder.merge(kmz, "2026-01-19", listOf(trip("t1768800000000")))
        val (kml, ids) = DailyKmz.inspect(kmz)
        assertEquals(1, Regex("<Folder>").findAll(kml!!).count())
        assertEquals(2, ids.size)
        assertTrue(kml.contains("-stop${pinStop.startMs}"))
        // the flat trip is a direct child: its placemark opens without id
        assertTrue(Regex("<Placemark>\n    <name>t1768800000000</name>").containsMatchIn(kml))
    }

    @Test
    fun legacyFlatPinsBecomeFolderChildrenOnRebuild() {
        // Hand-built pre-folder KMZ: flat track placemark + flat pin sibling.
        val track = DailyKmz.placemarkXml("2026-01-19 06.00.00", "t1768797600000", "FF1400FF",
            trip("t1768797600000").points)
        val pin = DailyKmz.stopPinXml("t1768797600000", pinStop, null)!!
        val flatKml = "<kml><Document>\n$track$pin</Document></kml>"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("2026-01-19.kml"))
            z.write(flatKml.toByteArray())
            z.closeEntry()
        }
        val kmz = DailyKmz(renderer = FakePlotRenderer)
            .merge(out.toByteArray(), "2026-01-19", listOf(trip("t1768797600000").copy(stops = listOf(pinStop))))
        val (kml, ids) = DailyKmz.inspect(kmz)
        assertEquals(setOf("t1768797600000"), ids)
        assertEquals(1, Regex("<Folder>").findAll(kml!!).count())
        assertEquals(1, Regex("<Placemark id=").findAll(kml).count()) // no duplicate pin
    }

    @Test
    fun pinsDoNotDisturbTripColorsAndRenameKeepsPins() {
        val builder = DailyKmz(renderer = FakePlotRenderer)
        var kmz = builder.merge(null, "2026-01-19", listOf(trip("t1768797600000").copy(stops = listOf(pinStop))))
        kmz = builder.merge(kmz, "2026-01-19", listOf(trip("t1768800000000")))
        var (kml, _) = DailyKmz.inspect(kmz)
        val colors = Regex("<color>(\\w+)</color>").findAll(kml!!).map { it.groupValues[1] }.toList()
        assertEquals(listOf("FF1400FF", "FF14F0FF"), colors) // pins consumed no color
        val renamed = DailyKmz.renamePlacemark(kmz, listOf("t1768797600000"), "Errand")
        kml = DailyKmz.inspect(renamed!!).first
        assertEquals(2, Regex("<name>Errand</name>").findAll(kml!!).count()) // folder + track
        assertTrue("rename lost the stop pin", kml.contains("-stop${pinStop.startMs}"))
        assertTrue("rename clobbered the pin name", kml.contains("<name>Stopped for 5:00</name>"))
    }
}
