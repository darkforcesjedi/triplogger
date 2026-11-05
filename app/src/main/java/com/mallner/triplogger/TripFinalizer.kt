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
    val saveOds: Boolean
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
            val pids = settings.pidHexes.ifEmpty { trip.pids() }
            val gpsRows = trip.readGpsRows()
            val obdRows = trip.readObdRows(pids)
            val startMs = (gpsRows.minOfOrNull { it.ts } ?: 0L).takeIf { it > 0 } ?: trip.startMs()

            if (startMs <= 0L || (gpsRows.isEmpty() && obdRows.isEmpty())) {
                return FinalizeResult.Skipped("empty journal")
            }

            val dayLabel = TripFormats.dayLabel(startMs)
            val label = TripFormats.tripLabel(startMs)
            val trackId = label.replace('.', ':')

            var wroteOds = false
            var kmzLabel: String? = null

            if (settings.saveOds && odsStorage != null) {
                odsStorage.write("engine-$label.ods", buildOds(obdRows, pids))
                wroteOds = true
            }

            if (settings.generateKmz && kmzStorage != null) {
                val tripTrack = buildTrip(label, trackId, gpsRows, obdRows)
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

    private fun buildOds(obdRows: List<ObdRow>, pids: List<String>): ByteArray {
        val headers = mutableListOf("Time", "GPS Time", "Latitude", "Longitude", "Altitude")
        val pidList = pids.mapNotNull { ObdPids.byHex(it) }
        pidList.forEach { headers.add("${it.name} (${it.unit})") }

        val rows = obdRows.map { row ->
            val cells = mutableListOf<Any?>()
            cells.add(TripFormats.deviceTime(row.ts))
            cells.add(row.gpsTime?.let { TripFormats.deviceTime(it) } ?: "")
            cells.add(row.latitude)
            cells.add(row.longitude)
            cells.add(row.altitude)
            pidList.forEach { pid -> cells.add(row.values[pid.hex]) }
            cells
        }
        return OdsExport.build("engine", headers, rows)
    }

    fun buildTrip(label: String, trackId: String, gpsRows: List<GpsRow>, obdRows: List<ObdRow>): TripTrack? {
        val points = gpsRows
            .sortedBy { it.gpsTime }
            .map { TrackPoint(it.gpsTime, it.longitude, it.latitude, if (it.altitude.isNaN()) 0.0 else it.altitude) }

        val gpsSpeeds = gpsRows
            .filter { !it.speedMps.isNaN() }
            .map { Pair(it.gpsTime, it.speedMps * TripFormats.MPS_TO_MPH.toFloat()) }

        val obdSpeeds = obdRows
            .mapNotNull { row -> row.values["0D"]?.let { Pair(row.ts, (it * TripFormats.KMH_TO_MPH).toFloat()) } }

        if (points.isEmpty() && gpsSpeeds.isEmpty() && obdSpeeds.isEmpty()) return null
        return TripTrack(trackId, label, points, obdSpeeds, gpsSpeeds)
    }
}
