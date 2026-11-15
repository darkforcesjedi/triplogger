package com.mallner.triplogger

import kotlin.math.cos
import kotlin.math.exp

/** Setting values for AppSettings.stopDetectionMode. */
object StopDetection {
    const val OFF = 0

    /** Confirmed stops are recorded on the trip as metadata. */
    const val ANNOTATE = 1

    /** A confirmed stop closes the current trip; motion resumes a new one. */
    const val SPLIT = 2
}

/**
 * A confirmed stop: the vehicle was stationary from [startMs] to [endMs]
 * centred on [latitude]/[longitude] (null when no GPS fixes existed during
 * the dwell). [source] is "wheel" or "gps" (which detector confirmed it).
 */
data class StopEvent(
    val startMs: Long,
    val endMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val source: String
) {
    val durationSec: Long get() = (endMs - startMs) / 1000
}

/**
 * In-memory idle/stop detector for one recording session. Pure logic (all
 * timestamps arrive from callers) so it is JVM-testable and replayable.
 *
 * Two detectors, one decision-maker: while the wheel-speed stream (PID 0D,
 * polled at 1 s whenever OBD is connected) is fresh, it alone decides; when
 * no sample is younger than [wheelStaleMs] the GPS detector takes over.
 * Confirmation sets BOTH machines to the stopped state so whichever signal
 * returns first also closes the stop, and closing resets both windows (the
 * GPS filter lags the wheel stream, so a genuine conflict is a non-case;
 * live-data evaluation on 41 real trips confirmed the ordering).
 *
 * Rules (tuned on real trip CSVs):
 * - wheel: speed == 0 begins the window; 3 consecutive scans with speed > 0
 *   before confirmation abort it; 3 consecutive > 0 scans after confirmation
 *   end the stop at the FIRST of those three scans.
 * - gps: exponential smoothing v' += a*(v - v'), a = 1 - exp(-dt/tau)
 *   (dt-correct so dropped samples behave); v' < [triggerMps] opens a window
 *   that caches raw samples; 3 consecutive scans with v' > [endMps] abort it;
 *   once [minDurationMs] is met, the cached positions must pass a spatial
 *   check: the centroid of all window positions, with at least
 *   [radiusFraction] of them within [radiusM], confirms the stop. The stop
 *   start is backdated to the first cached sample whose RAW speed was below
 *   the trigger, because the filter crosses late by design.
 *
 * A dwell becomes a [StopEvent] only when motion resumes: a trip that ends
 * during a dwell (parked at the destination) records NO stop - the end of a
 * trip is not a stop within it. [finishTrip] drops any open dwell.
 *
 * Two session-level corrections:
 * - Arming: no stop window opens until real movement was seen (3
 *   consecutive wheel scans > 0, or 3 GPS fixes above 1 m/s). Lingering
 *   in the driveway before the trip's first movement is the trip's
 *   beginning, not a stop, and must not split-trip a fresh journal.
 * - Merging: a noise burst or sensor blip mid-dwell closes one stop and
 *   re-detection opens another at the same place; a stop closing within
 *   [mergeGapMs] of the previous one at (nearly) the same coordinates is
 *   merged into it, so one physical stop yields ONE record. Merging
 *   defers each stop's [onClosed] until the next stop closes or the
 *   session's [finishTrip] flushes it (annotate mode drains at finalize,
 *   which runs after finishTrip, so nothing is lost).
 *
 * [onConfirmed] fires when a stop is confirmed but not yet ended (split-trip
 * mode closes the journal there), [onResumed] when a confirmed stop ends
 * (split-trip mode opens the next journal there), [onClosed] for every
 * completed stop (annotate mode).
 *
 * Thread-safety: the public feed methods synchronize; GPS fixes arrive on
 * the main looper while wheel samples arrive on the OBD poll thread. The
 * callbacks run under the detector lock.
 */
class StopDetector(
    val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    private val wheelStaleMs: Long = 3_000,
    private val tauMs: Long = 5_000,
    private val triggerMps: Double = 0.6,
    private val endMps: Double = 1.5,
    private val radiusM: Double = 25.0,
    private val radiusFraction: Double = 0.9,
    private val mergeGapMs: Long = 60_000,
    private val onConfirmed: (() -> Unit)? = null,
    private val onResumed: (() -> Unit)? = null,
    private val onClosed: ((StopEvent) -> Unit)? = null
) {
    companion object {
        const val DEFAULT_MIN_DURATION_MS = 120_000L

        /** GPS logging cap enforced while stop detection is enabled. */
        const val GPS_MAX_INTERVAL_SEC = 5

        /** The enforced wheel poll interval. */
        const val WHEEL_POLL_MS = 1_000L

        const val ABORT_SCANS = 3

        /** Raw GPS fixes that arm the detector (see class docs). */
        private const val GPS_ARM_MPS = 1.0

        fun enforcedGpsIntervalSec(userFreqSec: Int): Int =
            if (userFreqSec > GPS_MAX_INTERVAL_SEC) GPS_MAX_INTERVAL_SEC else userFreqSec.coerceAtLeast(1)

        /** Fixes kept for dwell centroids (~3.6 h at 2 s cadence). */
        private const val MAX_POSITIONS = 6_400
    }

    private enum class State { MOVING, CANDIDATE, STOPPED }

    // Wheel machine (0D km/h; exact zero is the parked value on this signal).
    private var wheelState = State.MOVING
    private var wheelWindowStartMs = 0L
    private var wheelRun = 0
    private var wheelRunFirstMs = 0L

    // GPS machine.
    private var gpsState = State.MOVING
    private var filteredMps: Double? = null
    private var lastGpsMs = 0L
    private var gpsWindow: MutableList<GpsSample> = mutableListOf()
    private var gpsRun = 0
    private var gpsRunFirstMs = 0L

    // Coordinator. 0 = "no wheel sample ever arrived" (GPS takes over at once).
    private var lastWheelMs = 0L
    private var openStartMs: Long? = null
    private var openSource: String? = null
    private val positions = ArrayDeque<GpsSample>()

    // No window opens until real movement was seen this session.
    private var armed = false
    private var wheelArmRun = 0
    private var gpsArmRun = 0

    // Last closed stop, held back so a noise-split continuation can merge
    // into it instead of recording the same physical stop twice.
    private var pendingClosed: StopEvent? = null

    @Synchronized
    fun onWheelSample(tsMs: Long, speedKmh: Double) {
        lastWheelMs = tsMs
        if (!armed) {
            if (speedKmh > 0.0) {
                if (++wheelArmRun >= ABORT_SCANS) armed = true
            } else {
                wheelArmRun = 0
            }
        }
        when (wheelState) {
            State.MOVING -> if (armed && speedKmh == 0.0) {
                wheelState = State.CANDIDATE
                wheelWindowStartMs = tsMs
                wheelRun = 0
            }

            State.CANDIDATE -> when {
                speedKmh == 0.0 -> {
                    wheelRun = 0
                    if (tsMs - wheelWindowStartMs >= minDurationMs) confirm(tsMs, "wheel")
                }

                else -> {
                    wheelRun++
                    if (wheelRun >= ABORT_SCANS) {
                        wheelState = State.MOVING
                        wheelRun = 0
                    }
                }
            }

            State.STOPPED -> when {
                speedKmh > 0.0 -> {
                    wheelRun++
                    if (wheelRun == 1) wheelRunFirstMs = tsMs
                    if (wheelRun >= ABORT_SCANS) closed(wheelRunFirstMs)
                }

                else -> wheelRun = 0
            }
        }
    }

    @Synchronized
    fun onGpsSample(tsMs: Long, latitude: Double, longitude: Double, speedMps: Double) {
        positions.addLast(GpsSample(tsMs, latitude, longitude, speedMps))
        while (positions.size > MAX_POSITIONS) positions.removeFirst()
        if (!armed) {
            if (speedMps > GPS_ARM_MPS) {
                if (++gpsArmRun >= ABORT_SCANS) armed = true
            } else {
                gpsArmRun = 0
            }
        }
        // Fresh wheel data decides; GPS only guards no-OBD / OBD-off gaps.
        if (lastWheelMs != 0L && tsMs - lastWheelMs < wheelStaleMs) return
        filteredMps = if (filteredMps == null) speedMps else {
            val dt = (tsMs - lastGpsMs).coerceAtLeast(0L)
            val a = if (dt > 0) 1.0 - exp(-dt.toDouble() / tauMs) else 0.0
            filteredMps!! + a * (speedMps - filteredMps!!)
        }
        lastGpsMs = tsMs
        val v = filteredMps!!
        when (gpsState) {
            State.MOVING -> if (armed && v < triggerMps) {
                gpsState = State.CANDIDATE
                gpsWindow = mutableListOf(GpsSample(tsMs, latitude, longitude, speedMps))
                gpsRun = 0
            }

            State.CANDIDATE -> {
                gpsWindow.add(GpsSample(tsMs, latitude, longitude, speedMps))
                if (v > endMps) {
                    gpsRun++
                    if (gpsRun >= ABORT_SCANS) {
                        gpsState = State.MOVING
                        gpsWindow = mutableListOf()
                        gpsRun = 0
                    }
                } else {
                    gpsRun = 0
                    if (tsMs - gpsWindow.first().tsMs >= minDurationMs) {
                        if (centroidPasses()) confirm(tsMs, "gps") else resetGpsWindow()
                    }
                }
            }

            State.STOPPED -> if (v > endMps) {
                gpsRun++
                if (gpsRun == 1) gpsRunFirstMs = tsMs
                if (gpsRun >= ABORT_SCANS) closed(gpsRunFirstMs)
            } else {
                gpsRun = 0
            }
        }
    }

    /**
     * Trip/session boundary: a dwell that never saw motion resume is dropped
     * (the end of a trip is not a stop), the held-back last stop is flushed,
     * and everything - including the movement arming - resets.
     */
    @Synchronized
    fun finishTrip() {
        val pending = pendingClosed
        pendingClosed = null
        resetAll()
        armed = false
        wheelArmRun = 0
        gpsArmRun = 0
        if (pending != null) onClosed?.invoke(pending)
    }

    private fun confirm(tsMs: Long, source: String) {
        // Backdate from the window BEFORE clearing it: the filter crossing
        // lags the physical stop, the raw sub-trigger sample does not.
        val start = if (source == "gps") backdatedGpsStartMs(tsMs) else wheelWindowStartMs
        // Both machines watch for the end: the confirming signal may go away
        // mid-dwell (ignition off kills the OBD stream entirely).
        wheelState = State.STOPPED
        wheelRun = 0
        gpsState = State.STOPPED
        gpsRun = 0
        gpsWindow = mutableListOf()
        filteredMps = null
        if (openStartMs == null) {
            openStartMs = start
            openSource = source
            onConfirmed?.invoke()
        }
    }

    private fun closed(endMs: Long) {
        val start = openStartMs
        val source = openSource
        val centroid = if (start != null) centroidOf(start, endMs) else null
        resetAll()
        if (start != null) {
            val event = StopEvent(
                startMs = start,
                endMs = endMs,
                latitude = centroid?.first,
                longitude = centroid?.second,
                source = source ?: "gps"
            )
            val prev = pendingClosed
            if (prev != null && event.startMs - prev.endMs <= mergeGapMs && samePlace(prev, event)) {
                // A blip split ONE physical stop in two: extend the record.
                pendingClosed = prev.copy(endMs = event.endMs)
            } else {
                if (prev != null) onClosed?.invoke(prev)
                pendingClosed = event
            }
        }
        onResumed?.invoke()
    }

    /** Missing coordinates (wheel-only stops) never block a time-based merge. */
    private fun samePlace(a: StopEvent, b: StopEvent): Boolean {
        val la = a.latitude ?: return true
        val lb = b.latitude ?: return true
        return groundMeters(la, a.longitude!!, lb, b.longitude!!) <= radiusM
    }

    /** Spatial confirmation: >= radiusFraction of window fixes near centroid. */
    private fun centroidPasses(): Boolean {
        if (gpsWindow.size < 2) return false
        val lat = gpsWindow.sumOf { it.lat } / gpsWindow.size
        val lon = gpsWindow.sumOf { it.lon } / gpsWindow.size
        val inside = gpsWindow.count { groundMeters(lat, lon, it.lat, it.lon) <= radiusM }
        return inside >= radiusFraction * gpsWindow.size
    }

    /** First cached raw sub-trigger sample: the physical start of the stop. */
    private fun backdatedGpsStartMs(latestMs: Long): Long =
        gpsWindow.firstOrNull { it.mps < triggerMps }?.tsMs
            ?: positions.lastOrNull { it.tsMs <= latestMs && it.mps < triggerMps }?.tsMs
            ?: latestMs

    private fun centroidOf(fromMs: Long, toMs: Long): Pair<Double, Double>? {
        var n = 0
        var lat = 0.0
        var lon = 0.0
        for (p in positions) if (p.tsMs in fromMs..toMs) {
            n++; lat += p.lat; lon += p.lon
        }
        if (n == 0) return null
        return lat / n to lon / n
    }

    private fun resetGpsWindow() {
        gpsState = State.MOVING
        gpsWindow = mutableListOf()
        gpsRun = 0
    }

    private fun resetAll() {
        wheelState = State.MOVING
        wheelRun = 0
        resetGpsWindow()
        filteredMps = null
        openStartMs = null
        openSource = null
    }

    private data class GpsSample(val tsMs: Long, val lat: Double, val lon: Double, val mps: Double)
}

/** Equirectangular ground distance in metres; fine for dwell-scale maths. */
internal fun groundMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
    val y = lat2 - lat1
    return 111_319.5 * kotlin.math.hypot(x, y)
}
