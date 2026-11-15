package com.mallner.triplogger

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Multiple independent trip-distance estimates (multi-source mileage).
 * Every method is computed from the recorded journals and persisted; the
 * Trips UI shows the best one by the priority [SOURCE_ODO] > [SOURCE_OBD] >
 * [SOURCE_GPS_SPEED] > [SOURCE_TRACK], with all values kept for comparison.
 *
 * Rationale per source:
 *  - odo:       dashboard odometer (Mode 01 PID A6) delta over the trip; the
 *               legal reference for expense reports, only when the car answers A6
 *  - obd:       trapezoidal integration of PID 0D wheel speed (what the driver
 *               sees on the speedometer, tire-size accurate)
 *  - gps_speed: trapezoidal integration of the GPS Doppler speed with a ZUPT
 *               style stationary gate (kills parked jitter, keeps slow crawls
 *               that the haversine floor below loses)
 *  - track:     haversine over consecutive fixes, segments below the jitter
 *               floor ignored (the legacy v1 method, GPS-only fallback)
 */
object DistanceEstimators {
    const val SOURCE_ODO = "odo"
    const val SOURCE_OBD = "obd"
    const val SOURCE_GPS_SPEED = "gps_speed"
    const val SOURCE_TRACK = "track"

    /** Stationary gate for the GPS speed integral (m/s). */
    internal const val ZUPT_SPEED_MPS = 0.4

    /** Haversine segment floor below which movement is treated as jitter. */
    internal const val JITTER_FLOOR_M = 10.0

    /** Integration steps across gaps longer than this are dropped (stops, splits). */
    internal const val MAX_STEP_S = 30.0

    /** The odometer delta is only trusted within this factor of the wheel-speed integral. */
    internal const val ODO_TOLERANCE = 0.15

    /** Minimum integrated distance before the odometer cross-check means anything. */
    private const val ODO_CHECK_MIN_M = 200.0

    data class Estimates(
        /** meters per source; null when the source had no usable data */
        val trackM: Double?,
        val gpsSpeedM: Double?,
        val obdSpeedM: Double?,
        val odoM: Double?,
        /** Why [odoM] was rejected (null when accepted or unavailable). */
        val odoRejectReason: String? = null
    ) {
        /** Persistable map: source -> meters, best first. Only present sources. */
        fun bySource(): Map<String, Double> = buildMap {
            odoM?.let { put(SOURCE_ODO, it) }
            obdSpeedM?.let { put(SOURCE_OBD, it) }
            gpsSpeedM?.let { put(SOURCE_GPS_SPEED, it) }
            trackM?.let { put(SOURCE_TRACK, it) }
        }

        /** [SOURCE_*] label of the best estimate, following the priority order. */
        fun bestSource(): String? = bySource().keys.sortedBy {
            listOf(SOURCE_ODO, SOURCE_OBD, SOURCE_GPS_SPEED, SOURCE_TRACK).indexOf(it)
        }.firstOrNull()

        fun bestMeters(): Double? = bestSource()?.let { bySource()[it] }
    }

    /**
     * Full trip estimates from recorded rows. [odoReadings] are
     * (ts, odometer km) pairs as journaled during the trip (may be empty).
     */
    fun estimates(gps: List<GpsRow>, obd: List<ObdRow>, odoReadings: List<Pair<Long, Double>>): Estimates {
        val track = trackMeters(gps)
        val gpsSpeed = gpsSpeedMeters(gps)
        val obdSpeed = obdSpeedMeters(obd)
        val (odo, reason) = odometerMeters(odoReadings, obdSpeed ?: gpsSpeed)
        return Estimates(
            trackM = track?.takeIf { !it.isNaN() && it > 0 },
            gpsSpeedM = gpsSpeed?.takeIf { !it.isNaN() && it > 0 },
            obdSpeedM = obdSpeed?.takeIf { !it.isNaN() && it > 0 },
            odoM = odo,
            odoRejectReason = reason
        )
    }

    /** Haversine path length with the jitter floor; null below two fixes. */
    fun trackMeters(gps: List<GpsRow>): Double? {
        val points = gps.sortedBy { it.gpsTime }.filter { !it.latitude.isNaN() && !it.longitude.isNaN() }
        if (points.size < 2) return null
        var total = 0.0
        for (i in 1 until points.size) {
            val d = haversineMeters(points[i - 1], points[i])
            if (d > JITTER_FLOOR_M) total += d
        }
        return total
    }

    /**
     * Trapezoidal integration of the GPS Doppler speed with ZUPT gating:
     * a step whose endpoints are both below [ZUPT_SPEED_MPS] is stationary
     * (parked jitter contributes nothing); fixes without a usable speed fall
     * back to the gated haversine segment for that step alone.
     */
    fun gpsSpeedMeters(gps: List<GpsRow>): Double? {
        val points = gps.sortedBy { it.gpsTime }.filter { !it.latitude.isNaN() && !it.longitude.isNaN() }
        if (points.size < 2) return null
        var total = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = (b.gpsTime - a.gpsTime) / 1000.0
            if (dt <= 0.0 || dt > MAX_STEP_S) continue
            val va = a.speedMps
            val vb = b.speedMps
            if (va.isNaN() || vb.isNaN() || va < 0f || vb < 0f) {
                val d = haversineMeters(a, b)
                if (d > JITTER_FLOOR_M) total += d
            } else if (va >= ZUPT_SPEED_MPS || vb >= ZUPT_SPEED_MPS) {
                total += (va + vb) / 2.0 * dt
            }
        }
        return total
    }

    /** Trapezoidal integration of PID 0D wheel speed (km/h stored). */
    fun obdSpeedMeters(obd: List<ObdRow>): Double? {
        val rows = obd.sortedBy { it.ts }
            .mapNotNull { row -> row.values["0D"]?.takeIf { !it.isNaN() }?.let { Pair(row.ts, it / 3.6) } }
        if (rows.size < 2) return null
        var total = 0.0
        for (i in 1 until rows.size) {
            val dt = (rows[i].first - rows[i - 1].first) / 1000.0
            if (dt <= 0.0 || dt > MAX_STEP_S) continue
            total += (rows[i - 1].second + rows[i].second) / 2.0 * dt
        }
        return total
    }

    /**
     * Odometer delta (meters). [readings] are (ts, km) pairs, oldest first.
     * Rejected (null + reason) when: fewer than two distinct readings, a
     * non-monotonic odometer, an implausible jump, or the delta deviates from
     * [wheelCheckMeters] beyond [ODO_TOLERANCE] (once the trip is long enough
     * for the cross-check to mean anything).
     */
    fun odometerMeters(readings: List<Pair<Long, Double>>, wheelCheckMeters: Double?): Pair<Double?, String?> {
        val valid = readings.filter { it.second > 0.0 && it.second < 4_000_000.0 }
        if (valid.size < 2) return Pair(null, "fewer than two readings")
        val first = valid.first().second
        val last = valid.last().second
        if (last <= first) return Pair(null, "odometer not increasing")
        var previous = first
        for ((_, km) in valid.drop(1)) {
            if (km < previous - 0.001) return Pair(null, "odometer rolled backwards")
            if (km - previous > 2.0) return Pair(null, "odometer jumped more than 2 km between reads")
            previous = km
        }
        val meters = (last - first) * 1000.0
        if (wheelCheckMeters != null && wheelCheckMeters > ODO_CHECK_MIN_M) {
            if (abs(meters - wheelCheckMeters) / wheelCheckMeters > ODO_TOLERANCE) {
                return Pair(null, "delta deviates over ${(ODO_TOLERANCE * 100).toInt()}% from wheel-speed distance")
            }
        }
        return Pair(meters, null)
    }

    /**
     * Live odometer plausibility gate used while recording: accepts a reading
     * only when it moves forward by at most [maxStepKm] (derived from speed)
     * since the last accepted value.
     */
    fun liveOdoAccepts(previousKm: Double?, readingKm: Double, maxStepKm: Double): Boolean {
        if (readingKm <= 0.0 || readingKm >= 4_000_000.0) return false
        val previous = previousKm ?: return true
        val step = readingKm - previous
        return step >= -0.001 && step <= maxStepKm
    }

    internal fun haversineMeters(a: GpsRow, b: GpsRow): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
