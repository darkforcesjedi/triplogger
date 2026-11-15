package com.mallner.triplogger

/**
 * Pure mapper from a legacy trip.properties map to trip metadata rows
 * (TODO "Trip metadata database" migration). No Android or org.json
 * dependencies so it is unit-testable on the JVM. The store applies the
 * result in a single transaction and deletes the properties file.
 */
object PropertiesImport {

    data class Metric(
        val kind: String,
        val source: String,
        val value: Double,
        val note: String? = null
    )

    data class Output(val kind: String, val name: String)

    data class Imported(
        /** Epoch track id "t<start_ms>", stable across renames. */
        val id: String,
        val folder: String,
        val state: String,
        /** trips table column name -> typed value (String/Long/Int). */
        val columns: Map<String, Any?>,
        val metrics: List<Metric>,
        val outputs: List<Output>,
        /** Unrecognized keys parked so future versions can adopt them. */
        val ext: Map<String, String>
    )

    sealed interface Result {
        data class Success(val trip: Imported) : Result
        data class Invalid(val reason: String) : Result
    }

    private val DISTANCE_SOURCES = setOf("odo", "obd", "gps_speed", "track")

    /** Distance property suffix -> metrics source. */
    private val DISTANCE_KEYS = mapOf(
        "distance_track_m" to "track",
        "distance_gps_speed_m" to "gps_speed",
        "distance_obd_m" to "obd",
        "distance_odo_m" to "odo"
    )

    /** Properties the mapper consumes entirely (never reach trip_ext). */
    private val COLUMN_KEYS = setOf(
        "start_ms", "end_ms", "entries", "segments", "pids", "title", "trigger",
        "tz_id", "tz_offset_min", "tz_name", "place_start", "place_end",
        "distance_src", "status_reason", "converted_at", "app_version"
    )
    private val METRIC_KEYS = DISTANCE_KEYS.keys +
        setOf("distance_m", "max_speed_mps", "odo_start_km", "odo_end_km")
    private val OUTPUT_KEYS = setOf("kmz_day", "ods_file") // become outputs rows
    private val DROPPED_KEYS = setOf("converted") // implied by converted_at

    /**
     * @param folder current journal folder name (becomes trips.folder).
     * @param state one of TripDatabase.STATE_PENDING/ARCHIVE/FAILED.
     * @param props raw key=value map as read from trip.properties.
     * @param nowMs import timestamp for created_at/updated_at.
     */
    fun import(folder: String, state: String, props: Map<String, String>, nowMs: Long): Result {
        if (folder.isBlank()) return Result.Invalid("blank folder name")
        if (state !in setOf(
                TripDatabase.STATE_PENDING,
                TripDatabase.STATE_ARCHIVE,
                TripDatabase.STATE_FAILED
            )
        ) {
            return Result.Invalid("unknown state '$state'")
        }
        val startMs = props["start_ms"]?.toLongOrNull()
        if (startMs == null || startMs <= 0L) return Result.Invalid("missing or invalid start_ms")

        val columns = linkedMapOf<String, Any?>()
        val metrics = mutableListOf<Metric>()
        val outputs = mutableListOf<Output>()
        val ext = linkedMapOf<String, String>()

        fun str(key: String): String? = props[key]?.trim()?.takeIf { it.isNotEmpty() }
        fun num(key: String): Double? = str(key)?.toDoubleOrNull()

        columns["start_ms"] = startMs
        str("end_ms")?.toLongOrNull()?.let { columns["end_ms"] = it }
        columns["entries"] = str("entries")?.toIntOrNull() ?: 0
        columns["segments"] = str("segments")?.toIntOrNull() ?: 0
        str("pids")?.let { pids ->
            columns["pids"] = pids.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(",").ifEmpty { null }
        }
        str("title")?.let { columns["title"] = it }
        str("trigger")?.let { columns["trigger"] = it }
        str("tz_id")?.let { columns["tz_id"] = it }
        str("tz_offset_min")?.toIntOrNull()?.let { columns["tz_offset_min"] = it }
        str("tz_name")?.let { columns["tz_name"] = it }
        str("place_start")?.let { columns["place_start"] = it }
        str("place_end")?.let { columns["place_end"] = it }
        str("status_reason")?.let { columns["status_reason"] = it }
        str("converted_at")?.toLongOrNull()?.let { columns["converted_at"] = it }
        str("app_version")?.let { columns["app_version"] = it }

        // distance_src: validated column, otherwise parked in ext.
        str("distance_src")?.let { src ->
            if (src in DISTANCE_SOURCES) columns["distance_source"] = src
            else ext["distance_src"] = src
        }

        // Per-method distance estimates; legacy distance_m only fills in
        // when distance_track_m is absent (they hold the same value).
        val distances = DISTANCE_KEYS.mapNotNull { (key, source) ->
            num(key)?.let { Metric("distance_m", source, it) }
        }
        metrics += distances
        if (distances.none { it.source == "track" }) {
            num("distance_m")?.let { metrics += Metric("distance_m", "track", it) }
        }
        num("max_speed_mps")?.let { metrics += Metric("max_speed_mps", "", it) }
        num("odo_start_km")?.let { metrics += Metric("odo_km", "start", it) }
        num("odo_end_km")?.let { metrics += Metric("odo_km", "end", it) }

        str("kmz_day")?.let { outputs += Output("kmz_day", "$it.kmz") }
        str("ods_file")?.let { outputs += Output("ods", it) }

        for ((key, value) in props) {
            if (key in COLUMN_KEYS || key in METRIC_KEYS || key in OUTPUT_KEYS ||
                key in DROPPED_KEYS ||
                (key == "distance_src" && columns.containsKey("distance_source"))
            ) continue
            ext[key] = value
        }
        columns["id"] = "t$startMs"
        columns["folder"] = folder
        columns["state"] = state
        columns["created_at"] = nowMs
        columns["updated_at"] = nowMs

        return Result.Success(
            Imported(
                id = "t$startMs",
                folder = folder,
                state = state,
                columns = columns,
                metrics = metrics,
                outputs = outputs,
                ext = ext
            )
        )
    }
}
