package com.mallner.triplogger

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts a recorded trip into final artifacts:
 *  - the daily Google Earth KMZ (merged into the existing file for that day)
 *  - an .ods spreadsheet of the selected PIDs (one row per poll cycle, each
 *    row stamped with the most recent GPS fix)
 *
 * Verification happens against the bytes in memory before the destination is
 * touched; the caller relocates the trip journal to success/ or failed/
 * depending on the returned result.
 */
data class FinalizeSettings(
    val pidHexes: List<String>,
    val generateKmz: Boolean,
    val saveOds: Boolean,
    /** Convert speed/temperature/pressure/distance outputs to US customary. */
    val usUnits: Boolean = true,
    /** Written into trip.properties at conversion (metadata-schema versioning). */
    val appVersion: Int = 0
)

sealed class FinalizeResult {
    data class Completed(val label: String, val wroteOds: Boolean, val kmzDayLabel: String?) : FinalizeResult()
    data class Skipped(val reason: String) : FinalizeResult()
    data class Failed(val error: Exception) : FinalizeResult()
}

object TripFinalizer {

    fun finalize(
        trip: OrphanTrip,
        settings: FinalizeSettings,
        kmzStorage: TripStorage?,
        odsStorage: TripStorage?,
        renderer: PlotRenderer
    ): FinalizeResult {
        return try {
            // The OBD CSV columns are positional and were written with the PID list the
            // trip recorded, so parse with that list - the current settings selection
            // can differ (changed sensors, rebuilt archive) and would misalign columns.
            val pids = trip.pids().ifEmpty { settings.pidHexes }
            val gpsRows = trip.readGpsRows()
            val obdRows = trip.readObdRows(pids)
            val startMs = (gpsRows.minOfOrNull { it.ts } ?: 0L).takeIf { it > 0 } ?: trip.startMs()

            if (startMs <= 0L || (gpsRows.isEmpty() && obdRows.isEmpty())) {
                return FinalizeResult.Skipped("empty journal")
            }

            // The zone the trip started in, from the recorded samples (fallback:
            // trip.properties, then device). Never the device zone at export time.
            val startTz = gpsRows.firstNotNullOfOrNull { it.tzOffsetMin }
                ?: obdRows.firstNotNullOfOrNull { it.tzOffsetMin }
                ?: trip.tzOffsetMin()

            val dayLabel = TripFormats.dayLabel(startMs, startTz)
            val label = TripFormats.tripLabel(startMs, startTz)
            // Stable epoch-based id; v1.1 used the wall-clock label, which can
            // collide across DST fall-back - the legacy id stays replaceable.
            val trackId = "t$startMs"
            val legacyTrackId = label.replace('.', ':')
            val title = trip.title()

            // Trip stats persisted into the journal metadata for the Trips tab.
            val endMs = (gpsRows.maxOfOrNull { it.ts } ?: 0L)
                .coerceAtLeast(obdRows.maxOfOrNull { it.ts } ?: 0L)
            val distanceM = tripDistanceMeters(gpsRows)
            val maxSpeedMps = gpsRows.maxOfOrNull { it.speedMps }?.takeIf { !it.isNaN() && it > 0 } ?: Float.NaN
            val entries = maxOf(gpsRows.size, obdRows.size)

            var wroteOds = false
            var kmzLabel: String? = null
            var odsFileName: String? = null

            if (settings.saveOds && odsStorage != null) {
                odsFileName = "engine-$label.ods"
                odsStorage.write(odsFileName, buildOds(obdRows, pids, settings.usUnits, startTz))
                wroteOds = true
            }

            if (settings.generateKmz && kmzStorage != null) {
                val tripTrack = buildTrip(label, trackId, gpsRows, obdRows, settings.usUnits, startTz)
                    ?.let { it.copy(title = title, legacyTrackId = legacyTrackId) }
                if (tripTrack != null) {
                    val existing = kmzStorage.read("$dayLabel.kmz")
                    val kmz = DailyKmz(renderer = renderer).merge(existing, dayLabel, listOf(tripTrack))
                    // Verify before replacing the existing file: the track must be present in the new bytes
                    val (_, ids) = DailyKmz.inspect(kmz)
                    if (trackId !in ids) {
                        return FinalizeResult.Failed(IllegalStateException("KMZ verification failed for $trackId"))
                    }
                    kmzStorage.write("$dayLabel.kmz", kmz)
                    kmzLabel = dayLabel
                }
            }

            // Persist derived metadata so the Trips tab shows rows without ever
            // reading CSVs (and renames/clears clear the failure marker).
            runCatching {
                trip.mergeProps(
                    mapOf(
                        "converted" to "1",
                        "converted_at" to System.currentTimeMillis().toString(),
                        "app_version" to settings.appVersion.toString(),
                        "end_ms" to endMs.toString(),
                        "entries" to entries.toString(),
                        "distance_m" to if (distanceM.isNaN()) null else "%.1f".format(distanceM),
                        "max_speed_mps" to if (maxSpeedMps.isNaN()) null else "%.2f".format(maxSpeedMps),
                        "segments" to trip.listFileNames()
                            .count { it.startsWith("gps-") || it == "gps.csv" }.toString(),
                        "kmz_day" to kmzLabel,
                        "ods_file" to odsFileName,
                        "status_reason" to null
                    )
                )
            }

            FinalizeResult.Completed(label, wroteOds, kmzLabel)
        } catch (e: Exception) {
            FinalizeResult.Failed(e)
        }
    }

    /**
     * Total path length over consecutive GPS fixes (haversine), NaN when
     * fewer than two fixes exist. Fixed-position jitter below 10 m is
     * ignored so a parked trip does not accumulate kilometers.
     */
    internal fun tripDistanceMeters(gpsRows: List<GpsRow>): Double {
        val points = gpsRows.sortedBy { it.gpsTime }.filter { !it.latitude.isNaN() && !it.longitude.isNaN() }
        if (points.size < 2) return Double.NaN
        var total = 0.0
        for (i in 1 until points.size) {
            val d = haversineMeters(points[i - 1], points[i])
            if (d > 10.0) total += d
        }
        return total
    }

    private fun haversineMeters(a: GpsRow, b: GpsRow): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Spreadsheet times carry the offset each row was recorded with (DST-safe). */
    private fun buildOds(obdRows: List<ObdRow>, pids: List<String>, us: Boolean, startTz: Int?): ByteArray {
        val headers = mutableListOf("Time", "GPS Time", "Latitude", "Longitude", "Altitude")
        val pidList = pids.mapNotNull { ObdPids.byHex(it) }
        pidList.forEach { headers.add("${it.name} (${UnitFormat.unitLabel(it.unit, us)})") }

        val rows = obdRows.map { row ->
            val cells = mutableListOf<Any?>()
            cells.add(TripFormats.odsTime(row.ts, row.tzOffsetMin ?: startTz))
            cells.add(row.gpsTime?.let { TripFormats.odsTime(it, row.tzOffsetMin ?: startTz) } ?: "")
            cells.add(row.latitude)
            cells.add(row.longitude)
            cells.add(row.altitude)
            pidList.forEach { pid ->
                val v = row.values[pid.hex]
                cells.add(if (v == null) null else UnitFormat.convert(pid.unit, v, us).first)
            }
            cells
        }
        return OdsExport.build("engine", headers, rows)
    }

    /** Time zone / DST changes across the trip as (time, new "+hh:mm") for the plot. */
    internal fun tzChanges(gpsRows: List<GpsRow>, obdRows: List<ObdRow>, startTz: Int?): List<Pair<Long, Int>> {
        val samples = (gpsRows.map { Pair(it.gpsTime, it.tzOffsetMin) } + obdRows.map { Pair(it.ts, it.tzOffsetMin) })
            .filter { it.first > 0L }
            .sortedBy { it.first }
        val changes = ArrayList<Pair<Long, Int>>()
        var previous = startTz
        for ((time, offset) in samples) {
            if (offset == null) continue
            if (previous != null && offset != previous) changes.add(Pair(time, offset))
            previous = offset
        }
        return changes
    }

    /**
     * Local calendar-day changes across the trip as (time, new "MM-dd") - either
     * from plain elapsed time or from an offset jump moving the wall clock over
     * midnight.
     */
    internal fun dateChanges(gpsRows: List<GpsRow>, obdRows: List<ObdRow>, startTz: Int?): List<Pair<Long, String>> {
        val samples = (gpsRows.map { Pair(it.gpsTime, it.tzOffsetMin ?: startTz) } +
            obdRows.map { Pair(it.ts, it.tzOffsetMin ?: startTz) })
            .filter { it.first > 0L }
            .sortedBy { it.first }
        val changes = ArrayList<Pair<Long, String>>()
        var previousDay: String? = null
        for ((time, offset) in samples) {
            val day = TripFormats.dayLabel(time, offset)
            if (previousDay != null && day != previousDay) changes.add(Pair(time, day.substring(5)))
            previousDay = day
        }
        return changes
    }

    fun buildTrip(
        label: String,
        trackId: String,
        gpsRows: List<GpsRow>,
        obdRows: List<ObdRow>,
        us: Boolean = true,
        startTz: Int? = null
    ): TripTrack? {
        val points = gpsRows
            .sortedBy { it.gpsTime }
            .map { TrackPoint(it.gpsTime, it.longitude, it.latitude, if (it.altitude.isNaN()) 0.0 else it.altitude, it.tzOffsetMin ?: startTz) }

        val gpsSpeeds = gpsRows
            .filter { !it.speedMps.isNaN() }
            .map { Pair(it.gpsTime, UnitFormat.mpsToDisplay(it.speedMps.toDouble(), us).toFloat()) }

        val obdSpeeds = obdRows
            .mapNotNull { row -> row.values["0D"]?.let { Pair(row.ts, UnitFormat.convert("km/h", it, us).first.toFloat()) } }

        // A KMZ track needs positions: trips recorded without GPS (or with no fix)
        // must not create an empty placemark with an OBD-only plot attached.
        if (points.isEmpty()) return null
        return TripTrack(
            trackId = trackId,
            label = label,
            speedUnit = UnitFormat.speedUnitLabel(us),
            points = points,
            obdSpeeds = obdSpeeds,
            gpsSpeeds = gpsSpeeds,
            tzChanges = tzChanges(gpsRows, obdRows, startTz),
            dateChanges = dateChanges(gpsRows, obdRows, startTz),
            tzOffsetMin = startTz
        )
    }
}
