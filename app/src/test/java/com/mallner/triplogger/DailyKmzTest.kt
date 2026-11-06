package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

object FakePlotRenderer : PlotRenderer {
    override fun renderPng(label: String, speedUnit: String, tzOffsetMin: Int?, tzChanges: List<Pair<Long, String>>, dateChanges: List<Pair<Long, String>>, obdSpeeds: List<Pair<Long, Float>>, gpsSpeeds: List<Pair<Long, Float>>): ByteArray =
        "PNG:$label".toByteArray()
}

class DailyKmzTest {

    private fun trip(id: String, points: Int = 3): TripTrack {
        val base = 1768797600000L // 2026-01-19 06:00 local-ish
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
    }
}
