package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule table lives in StopDetector's KDoc; these tests replay synthetic
 * streams shaped like the recorded live-device CSVs (wheel 1 s, GPS 2-5 s,
 * instant speed drops, creep spikes) against it. Default tuning is kept
 * explicit so parameter changes show up as test diffs.
 */
class StopDetectorTest {
    private val base = 1_700_000_000_000L
    private val lat0 = 31.7653
    private val lon0 = -82.3015

    private class Sink {
        val closed = mutableListOf<StopEvent>()
        val events = mutableListOf<String>()
    }

    private fun detector(sink: Sink, minD: Long = 60_000) = StopDetector(
        minDurationMs = minD,
        onConfirmed = { sink.events += "confirmed" },
        onResumed = { sink.events += "resumed" },
        onClosed = { sink.closed += it }
    )

    /** 1 s wheel cadence; one entry per second, offset seconds from [t0]. */
    private fun wheel(d: StopDetector, t0: Long, seconds: Int, kmh: Double) {
        for (s in 0 until seconds) d.onWheelSample(t0 + s * 1000, kmh)
    }

    /** GPS cadence fixes; lat advances with speed so motion is real. */
    private fun gps(
        d: StopDetector,
        t0: Long,
        seconds: Int,
        mps: Double,
        stepSec: Int = 5,
        driftMps: Double = 0.0
    ) {
        var lat = lat0
        var t = t0
        var i = 0
        while (i < seconds) {
            d.onGpsSample(t, lat, lon0, mps)
            lat += (mps + driftMps) * stepSec / 111_319.5
            t += stepSec * 1000L
            i += stepSec
        }
    }

    // ------------------------------------------------------------------ wheel

    @Test
    fun wheelStopEmitsAtResumeWithBackdatedStart() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        val stopStart = base + 30_000
        wheel(d, stopStart, 70, 0.0)
        assertEquals(emptyList<String>(), sink.closed.map { it.source })
        assertEquals(listOf("confirmed"), sink.events) // nothing before resume
        val resume = stopStart + 70_000
        wheel(d, resume, 10, 40.0)
        assertEquals("held back for potential merge", 0, sink.closed.size)
        d.finishTrip()
        assertEquals(1, sink.closed.size)
        val e = sink.closed[0]
        assertEquals(stopStart, e.startMs)
        assertEquals(resume, e.endMs) // first of the three resume scans
        assertEquals("wheel", e.source)
        assertEquals(listOf("confirmed", "resumed"), sink.events)
    }

    @Test
    fun tripEndingDuringDwellRecordsNoStop() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        wheel(d, base + 30_000, 130, 0.0) // confirmed, never resumed
        d.finishTrip()
        assertEquals(0, sink.closed.size)
        assertEquals(listOf("confirmed"), sink.events)
    }

    @Test
    fun shortDwellIsNotAStop() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        wheel(d, base + 30_000, 20, 0.0)
        wheel(d, base + 50_000, 30, 40.0)
        assertEquals(0, sink.closed.size)
        assertEquals(emptyList<String>(), sink.events)
    }

    @Test
    fun threePositiveScansAbortTheCandidateWindow() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        wheel(d, base + 30_000, 40, 0.0)
        wheel(d, base + 70_000, 3, 2.0) // creep spike, >= 3 scans
        wheel(d, base + 73_000, 40, 0.0) // new window, too short to reach 60 s
        wheel(d, base + 113_000, 10, 40.0)
        assertEquals(0, sink.closed.size)
    }

    @Test
    fun twoPositiveScansDoNotAbort() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        val stopStart = base + 30_000
        wheel(d, stopStart, 20, 0.0)
        wheel(d, stopStart + 20_000, 2, 1.0) // below the abort threshold
        wheel(d, stopStart + 22_000, 45, 0.0)
        wheel(d, stopStart + 67_000, 5, 40.0)
        d.finishTrip()
        assertEquals(1, sink.closed.size)
        assertEquals(stopStart, sink.closed[0].startMs)
    }

    @Test
    fun alternatingCreepNeverReachesThreeConsecutiveScans() {
        // User rule is strict "3 CONSECUTIVE positive scans": an alternating
        // 0,2,0,2 stream never aborts the window, so the dwell confirms on
        // elapsed time and only a genuine 3-scan resume ends the stop.
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        var t = base + 30_000
        repeat(79) { i -> // ends on a zero scan so the next steps are clean
            d.onWheelSample(t, if (i % 2 == 0) 0.0 else 2.0)
            t += 1000
        }
        assertEquals(1, sink.events.count { it == "confirmed" })
        assertEquals(0, sink.closed.size) // still open: no 3 consecutive yet
        wheel(d, t, 2, 40.0)
        assertEquals(0, sink.closed.size) // only two positive scans
        wheel(d, t + 10_000, 5, 0.0)
        assertEquals(0, sink.closed.size) // run resets on returning to zero
        wheel(d, t + 15_000, 5, 40.0)
        d.finishTrip()
        assertEquals(1, sink.closed.size)
    }

    // -------------------------------------------------------------------- gps

    @Test
    fun gpsStopConfirmsAndClosesWithCentroidLocation() {
        val sink = Sink()
        val d = detector(sink)
        gps(d, base, 60, 13.9) // ~50 km/h for a minute
        val stopStart = base + 60_000
        gps(d, stopStart, 140, 0.0)
        val resume = stopStart + 140_000
        gps(d, resume, 30, 13.9)
        d.finishTrip()
        assertEquals(1, sink.closed.size)
        val e = sink.closed[0]
        assertEquals("gps", e.source)
        // EMA (tau 5 s at 5 s cadence) crosses the trigger a few samples in;
        // backdating lands at the first raw sub-trigger fix, never before
        // the physical stop nor more than ~3 samples late.
        assertTrue("start late: ${e.startMs - stopStart}", e.startMs - stopStart in 0..20_000)
        assertTrue("end early: ${e.endMs - resume}", e.endMs in resume until resume + 15_000)
        // stationary fixes: centroid is the stop position
        assertEquals(lat0, e.latitude!!, 1e-6)
    }

    @Test
    fun gpsOnlyDetectsWhenWheelStreamIsStale() {
        val sink = Sink()
        val d = detector(sink)
        // Fresh wheel says 40 km/h while GPS sits still: no stop.
        var t = base
        repeat(130) {
            d.onWheelSample(t, 40.0)
            d.onGpsSample(t, lat0, lon0, 0.0)
            t += 1000
        }
        assertEquals(0, sink.closed.size)
    }

    @Test
    fun wheelConfirmedStopClosedByGpsResumeAfterIgnitionOff() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        val stopStart = base + 30_000
        wheel(d, stopStart, 130, 0.0) // confirms at +60 s; power dies there
        // GPS takes over once the wheel stream is stale; driver sits, leaves,
        // comes back and drives off (all after the wheel went away).
        gps(d, stopStart + 131_000, 80, 0.0)
        val resume = stopStart + 211_000
        gps(d, resume, 30, 13.9)
        d.finishTrip()
        assertEquals(1, sink.closed.size)
        assertEquals("wheel", sink.closed[0].source)
        assertEquals(stopStart, sink.closed[0].startMs)
        assertTrue(sink.closed[0].endMs >= resume)
    }

    @Test
    fun crawlingQueuePassesSpeedTriggerButFailsTheRadiusCensus() {
        // Queue creep below the speed trigger is caught spatially: at the
        // production 120 s minimum a 0.5 m/s crawl spans ~60 m, well past
        // the 25 m centroid radius. (At an aggressive 60 s minimum a slow
        // crawl can slip through; the tuning notes call this trade-off.)
        val sink = Sink()
        val d = detector(sink, minD = 120_000)
        gps(d, base, 60, 13.9)
        // stop-window speed below the trigger but 0.5 m/s of real travel
        gps(d, base + 60_000, 200, 0.5, driftMps = 0.0)
        gps(d, base + 260_000, 40, 13.9)
        assertEquals(0, sink.closed.size)
    }

    @Test
    fun redLightBelowMinimumDurationIsIgnored() {
        val sink = Sink()
        val d = detector(sink)
        gps(d, base, 60, 13.9)
        gps(d, base + 60_000, 40, 0.0)
        gps(d, base + 100_000, 60, 13.9)
        assertEquals(0, sink.closed.size)
    }

    @Test
    fun noLocationMeansNullCoordinatesNotMissingStop() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        val stopStart = base + 30_000
        wheel(d, stopStart, 70, 0.0)
        wheel(d, stopStart + 70_000, 10, 40.0)
        d.finishTrip()
        assertEquals(1, sink.closed.size)
        assertNull(sink.closed[0].latitude)
    }

    @Test
    fun enforcesGpsCadenceCap() {
        assertEquals(5, StopDetector.enforcedGpsIntervalSec(10))
        assertEquals(5, StopDetector.enforcedGpsIntervalSec(5))
        assertEquals(3, StopDetector.enforcedGpsIntervalSec(3))
        assertEquals(1, StopDetector.enforcedGpsIntervalSec(0))
    }

    // -------------------------------------------------- arming and stop merges

    @Test
    fun lingeringAtTripStartIsNotAStop() {
        val sink = Sink()
        val d = detector(sink)
        // Sitting in the driveway past the dwell minimum BEFORE any movement:
        wheel(d, base, 130, 0.0)
        assertEquals(emptyList<String>(), sink.events) // never even confirmed
        wheel(d, base + 130_000, 30, 40.0) // first movement arms
        wheel(d, base + 160_000, 70, 0.0)
        wheel(d, base + 230_000, 10, 40.0)
        d.finishTrip()
        assertEquals(1, sink.closed.size)
        assertEquals(base + 160_000, sink.closed[0].startMs)
    }

    @Test
    fun lingeringGpsStartNeedsMovementToArm() {
        val sink = Sink()
        val d = detector(sink)
        gps(d, base, 130, 0.0) // stationary from session start: no window
        gps(d, base + 130_000, 60, 13.9) // arms on raw fixes over 1 m/s
        val stopStart = base + 190_000
        gps(d, stopStart, 140, 0.0)
        gps(d, stopStart + 140_000, 30, 13.9)
        d.finishTrip()
        assertEquals(1, sink.closed.size)
        assertEquals("gps", sink.closed[0].source)
    }

    @Test
    fun noiseBlipMidDwellMergesIntoOneStop() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        val stopStart = base + 30_000
        wheel(d, stopStart, 70, 0.0)
        val blip = stopStart + 70_000
        wheel(d, blip, 3, 3.0) // sensor burst: technically ends stop #1
        val stop2 = blip + 3_000
        wheel(d, stop2, 70, 0.0) // re-confirm at the same place
        wheel(d, stop2 + 70_000, 5, 40.0)
        d.finishTrip()
        assertEquals("one physical stop, one record", 1, sink.closed.size)
        val e = sink.closed[0]
        assertEquals(stopStart, e.startMs)
        assertEquals(stop2 + 70_000, e.endMs) // the true resume
    }

    @Test
    fun genuinelySeparateStopsStaySeparate() {
        val sink = Sink()
        val d = detector(sink)
        wheel(d, base, 30, 40.0)
        wheel(d, base + 30_000, 70, 0.0)
        wheel(d, base + 100_000, 130, 40.0) // driving well beyond the merge gap
        wheel(d, base + 230_000, 70, 0.0)
        wheel(d, base + 300_000, 10, 40.0)
        d.finishTrip()
        assertEquals(2, sink.closed.size)
        assertEquals(base + 30_000, sink.closed[0].startMs)
        assertEquals(base + 230_000, sink.closed[1].startMs)
    }

    // ------------------------------------------------------- real-data replay

    /**
     * Optional replay of real recorded journals (folder of trips, each with
     * gps-*.csv / obd-*.csv as the app writes them). Enabled by exporting
     * STOPEVAL_FIXTURE_DIR=/path/to/archive (run with --no-daemon so the
     * variable reaches the test JVM); skipped in normal runs so no recorded
     * data enters the repository. Guards the end-of-trip rule on
     * real signal: with a 120 s minimum the whole fleet yields exactly the
     * single mid-trip stop that resumed (20261113_171213); every other
     * dwell (arrival idling after power-off) must be dropped, never
     * recorded.
     */
    @Test
    fun realArchiveRecordsOnlyResumedStops() {
        val dir = System.getenv("STOPEVAL_FIXTURE_DIR")
        org.junit.Assume.assumeTrue(dir != null && java.io.File(dir).isDirectory)
        var recorded = 0
        var resumedMidTrip = 0
        java.io.File(dir!!).listFiles { f -> f.isDirectory }?.sorted() ?.forEach { tripDir ->
            val stops = mutableListOf<StopEvent>()
            val d = StopDetector(minDurationMs = 120_000, onClosed = { stops += it })
            replayTrip(tripDir, d)
            d.finishTrip()
            recorded += stops.size
            stops.forEach { assertTrue(it.durationSec >= 120) }
            if (stops.isNotEmpty()) {
                // Every recorded stop must have resumed before the journal
                // ends; the only fleet-wide case with a 120 s+ resumed dwell
                // is this one mid-trip stop.
                assertEquals("20261113_171213", tripDir.name)
                resumedMidTrip++
            }
        }
        assertEquals("exactly the one resumed mid-trip stop", 1, recorded)
        assertEquals(1, resumedMidTrip)
    }

    private fun replayTrip(tripDir: java.io.File, d: StopDetector) {
        val gps = mutableListOf<ReplaySample>()
        val wheel = mutableListOf<Pair<Long, Double>>()
        tripDir.listFiles { f -> f.name.startsWith("gps-") && f.name.endsWith(".csv") }?.forEach { f ->
            f.readLines().drop(1).forEach l@{ line ->
                val c = line.split(',')
                if (c.size < 7) return@l
                runCatching {
                    gps += ReplaySample(c[0].toLong(), c[2].toDouble(), c[3].toDouble(), c[5].toDouble())
                }
            }
        }
        tripDir.listFiles { f -> f.name.startsWith("obd-") && f.name.endsWith(".csv") }?.forEach { f ->
            val header = f.firstLineOrNull()?.split(',') ?: return@forEach
            val idx = header.indexOfFirst { it.trim() == "0D" }
            if (idx < 0) return@forEach
            f.readLines().drop(1).forEach l@{ line ->
                val c = line.split(',')
                if (c.size <= idx) return@l
                val v = c[idx].trim()
                if (v.isNotEmpty()) runCatching { wheel += c[0].toLong() to v.toDouble() }
            }
        }
        // merge-interleave by timestamp; wheel before GPS at equal stamps
        val events = mutableListOf<Pair<Long, Any>>() // ts -> Sample | Double
        gps.forEach { events += it.ts to it }
        wheel.forEach { events += it.first to it.second }
        events.sortWith(compareBy({ it.first }, { if (it.second is Double) 0 else 1 }))
        var lastWheelFed = 0L
        for ((ts, payload) in events) when (payload) {
            is Double -> if (ts - lastWheelFed >= 900) { // keep ~1 s cadence
                d.onWheelSample(ts, payload)
                lastWheelFed = ts
            }

            is ReplaySample -> d.onGpsSample(ts, payload.lat, payload.lon, payload.mps)
        }
    }

    private data class ReplaySample(val ts: Long, val lat: Double, val lon: Double, val mps: Double)

    private fun java.io.File.firstLineOrNull(): String? =
        bufferedReader().use { it.readLine() }
}
