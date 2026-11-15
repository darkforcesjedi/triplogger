package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.cos

/**
 * Synthetic traces for the multi-source mileage estimators: parked jitter,
 * a slow crawl below the haversine floor, highway speeds, OBD-absent trips
 * and odometer validation edges (the scenarios from the TODO spec).
 */
class DistanceEstimatorsTest {

    private val lat = 37.39
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(lat))

    /** A fix `meters` east of the origin at `timeMs` with the given speed. */
    private fun gpsAt(timeMs: Long, meters: Double, speedMps: Float, valid: Boolean = true) = GpsRow(
        ts = timeMs, gpsTime = timeMs,
        latitude = if (valid) lat else Double.NaN,
        longitude = if (valid) -122.08 + meters / metersPerDegLon else Double.NaN,
        altitude = 0.0, speedMps = speedMps, bearing = 90f, accuracyM = 3f
    )

    /** Straight drive at constant speed, fixes every 1000 ms. */
    private fun straightRun(speedMps: Double, seconds: Int, jitter: (Int) -> Double = { 0.0 }): List<GpsRow> =
        (0..seconds).map { i ->
            gpsAt(i * 1000L, speedMps * i + jitter(i), speedMps.toFloat())
        }

    @Test
    fun parkedJitterAccumulatesNothing() {
        // GPS parked at one point: positions wander +-5 m, speed reads 0.
        val rows = (0..120).map { i ->
            gpsAt(i * 1000L, if (i % 2 == 0) 0.0 else 5.0, 0f)
        }
        assertEquals(0.0, DistanceEstimators.trackMeters(rows)!!, 0.001)
        assertEquals(0.0, DistanceEstimators.gpsSpeedMeters(rows)!!, 0.001)
    }

    @Test
    fun crawlIsNotUndershotByTheSpeedIntegral() {
        // 3 m/s puts the 1 Hz haversine segments below the 10 m jitter floor:
        // track under-counts, the GPS speed integral must match reality.
        val actual = 3.0 * 120
        val rows = straightRun(3.0, 120)
        val track = DistanceEstimators.trackMeters(rows)!!
        val gpsSpeed = DistanceEstimators.gpsSpeedMeters(rows)!!
        assertEquals(actual, gpsSpeed, actual * 0.02)
        assertTrue("track should undershoot crawl ($track)", track < actual * 0.5)
    }

    @Test
    fun highwaySpeedsAgreeAcrossSources() {
        val actual = 25.0 * 400 // 10 km at 90 km/h
        val rows = straightRun(25.0, 400)
        assertEquals(actual, DistanceEstimators.trackMeters(rows)!!, actual * 0.02)
        assertEquals(actual, DistanceEstimators.gpsSpeedMeters(rows)!!, actual * 0.02)
        val obd = (0..400).map { i ->
            ObdRow(i * 1000L, null, null, null, null, mapOf("0D" to 90.0))
        }
        assertEquals(actual, DistanceEstimators.obdSpeedMeters(obd)!!, actual * 0.02)
    }

    @Test
    fun gapsLargerThanTheStepCapAreDropped() {
        // A 5-minute stop inside the run must not interpolate distance.
        val rows = straightRun(20.0, 60) + straightRun(20.0, 60).map {
            it.copy(gpsTime = it.gpsTime + 60_000 + 300_000, ts = it.ts + 60_000 + 300_000)
        }
        val gpsSpeed = DistanceEstimators.gpsSpeedMeters(rows)!!
        assertEquals(2 * 20.0 * 60, gpsSpeed, 20.0 * 60 * 0.05)
    }

    @Test
    fun missingSpeedFallsBackToGatedTrackPerStep() {
        val rows = listOf(
            gpsAt(0, 0.0, Float.NaN),
            gpsAt(1000, 25.0, Float.NaN),
            gpsAt(2000, 50.0, Float.NaN)
        )
        assertEquals(50.0, DistanceEstimators.gpsSpeedMeters(rows)!!, 5.0)
    }

    @Test
    fun odometerDeltaValidity() {
        val readings = listOf(Pair(0L, 57215.8), Pair(15_000L, 57216.8), Pair(30_000L, 57217.3))
        val (meters, reason) = DistanceEstimators.odometerMeters(readings, wheelCheckMeters = 1450.0)
        assertNull(reason)
        assertEquals(1500.0, meters!!, 0.001)

        // Deviating over the tolerance from the wheel-speed integral -> reject.
        val (bad, badReason) = DistanceEstimators.odometerMeters(readings, wheelCheckMeters = 5000.0)
        assertNull(bad)
        assertTrue(badReason!!.contains("deviates"))

        // Non-monotonic -> reject.
        val rolling = listOf(Pair(0L, 500.2), Pair(15_000L, 500.1), Pair(30_000L, 500.3))
        val (roll, rollReason) = DistanceEstimators.odometerMeters(rolling, null)
        assertNull(roll)
        assertTrue(rollReason!!.contains("backwards"))

        // Single reading -> not computable yet.
        val (one, oneReason) = DistanceEstimators.odometerMeters(listOf(Pair(0L, 100.0)), null)
        assertNull(one)
        assertTrue(oneReason!!.contains("two readings"))
    }

    @Test
    fun sourcePriorityAndMap() {
        val estimates = DistanceEstimators.Estimates(
            trackM = 900.0, gpsSpeedM = 1000.0, obdSpeedM = 1010.0, odoM = 1005.0
        )
        assertEquals(DistanceEstimators.SOURCE_ODO, estimates.bestSource())
        assertEquals(
            listOf("odo", "obd", "gps_speed", "track"),
            estimates.bySource().keys.toList()
        )
        val noOdo = estimates.copy(odoM = null)
        assertEquals(DistanceEstimators.SOURCE_OBD, noOdo.bestSource())
        assertEquals(
            DistanceEstimators.SOURCE_GPS_SPEED,
            estimates.copy(odoM = null, obdSpeedM = null).bestSource()
        )
        assertEquals(
            DistanceEstimators.SOURCE_TRACK,
            estimates.copy(odoM = null, obdSpeedM = null, gpsSpeedM = null).bestSource()
        )
    }

    @Test
    fun estimatesOnGpsOnlyTrip() {
        val rows = straightRun(15.0, 200)
        val estimates = DistanceEstimators.estimates(rows, emptyList(), emptyList())
        assertNotNull(estimates.trackM)
        assertNotNull(estimates.gpsSpeedM)
        assertNull(estimates.obdSpeedM)
        assertNull(estimates.odoM)
        assertEquals(DistanceEstimators.SOURCE_GPS_SPEED, estimates.bestSource())
    }

    @Test
    fun liveOdometerGate() {
        assertTrue(DistanceEstimators.liveOdoAccepts(null, 57215.8, 0.5))
        assertTrue(DistanceEstimators.liveOdoAccepts(57215.8, 57215.9, 0.5))
        assertTrue(DistanceEstimators.liveOdoAccepts(57215.8, 57215.8, 0.5))
        assertTrue(DistanceEstimators.liveOdoAccepts(57215.8, 57216.2, 0.5))
        // jumps, zeros, and "all FF" garbage
        assertTrue(!DistanceEstimators.liveOdoAccepts(57215.8, 57216.4, 0.5))
        assertTrue(!DistanceEstimators.liveOdoAccepts(57215.8, 0.0, 0.5))
        assertTrue(!DistanceEstimators.liveOdoAccepts(57215.8, 429496729.5, 0.5))
    }

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun odometerReadingsRoundTripThroughJournal() {
        val store = FileJournalStore(tmp.newFolder("journal"))
        val journal = store.createTrip("20261114_060000", 1768798800000L, listOf("0D"))
        journal.append(GpsRow(1000, 1000, lat, -122.08, 40.0, 12.5f, 180f, 5f))
        journal.appendOdometer(1500, 57215.8)
        journal.appendOdometer(16_500, 57216.0)
        journal.close()

        val trip = store.orphanOf(journal)
        val readings = trip.readOdoReadings()
        assertEquals(2, readings.size)
        assertEquals(57215.8, readings[0].second, 0.001)
        assertEquals(57216.0, readings[1].second, 0.001)
        assertEquals(1, journal.entriesWritten) // odometer lines are not logging ticks
        assertTrue(trip.distanceEstimates().isEmpty())
        assertNull(trip.distanceSrc())
    }
}
