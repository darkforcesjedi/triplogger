package com.mallner.triplogger

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
    val usUnits: Boolean = true
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
            val trackId = label.replace('.', ':')

            var wroteOds = false
            var kmzLabel: String? = null

            if (settings.saveOds && odsStorage != null) {
                odsStorage.write("engine-$label.ods", buildOds(obdRows, pids, settings.usUnits, startTz))
                wroteOds = true
            }

            if (settings.generateKmz && kmzStorage != null) {
                val tripTrack = buildTrip(label, trackId, gpsRows, obdRows, settings.usUnits, startTz)
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

            FinalizeResult.Completed(label, wroteOds, kmzLabel)
        } catch (e: Exception) {
            FinalizeResult.Failed(e)
        }
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
    internal fun tzChanges(gpsRows: List<GpsRow>, obdRows: List<ObdRow>, startTz: Int?): List<Pair<Long, String>> {
        val samples = (gpsRows.map { Pair(it.gpsTime, it.tzOffsetMin) } + obdRows.map { Pair(it.ts, it.tzOffsetMin) })
            .filter { it.first > 0L }
            .sortedBy { it.first }
        val changes = ArrayList<Pair<Long, String>>()
        var previous = startTz
        for ((time, offset) in samples) {
            if (offset == null) continue
            if (previous != null && offset != previous) changes.add(Pair(time, TripFormats.offsetLabel(offset)))
            previous = offset
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

        if (points.isEmpty() && gpsSpeeds.isEmpty() && obdSpeeds.isEmpty()) return null
        return TripTrack(trackId, label, UnitFormat.speedUnitLabel(us), points, obdSpeeds, gpsSpeeds, tzChanges(gpsRows, obdRows, startTz), startTz)
    }
}
