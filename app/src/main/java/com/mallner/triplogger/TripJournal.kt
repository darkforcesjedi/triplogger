package com.mallner.triplogger

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

data class GpsRow(
    val ts: Long,               // device time of the fix (write time)
    val gpsTime: Long,          // time reported by the GPS receiver
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,       // meters, 0.0 when unavailable
    val speedMps: Float,
    val bearing: Float,
    val accuracyM: Float
)

data class ObdRow(
    val ts: Long,
    val gpsTime: Long?,         // most recent GPS fix at poll time (null before first fix)
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val values: Map<String, Double?>  // pid hex -> value (null = no response this cycle)
)

/**
 * Crash-safe on-disk buffer for trips, inside a user-selectable directory with
 * the layout:
 *
 *   <root>/pending/<tripId>/trip.properties, gps.csv, obd.csv   (recording)
 *   <root>/success/<tripId>/...                                   (converted)
 *   <root>/failed/<tripId>/...                                    (conversion error)
 *
 * Journals are appended while driving; when a trip completes it is converted to
 * the final formats (KMZ/ODS) and relocated to success/ or failed/. Whatever is
 * still in pending/ when the service starts is an interrupted trip and gets
 * recovered (finalized) automatically.
 */
interface JournalStore {
    fun createTrip(tripId: String, startMs: Long, pids: List<String>): LiveJournal

    /** Every trip found in pending/ and failed/, oldest first. */
    fun unfinishedTrips(): List<OrphanTrip>

    /** Move a trip directory into the success/ or failed/ subfolder. */
    fun relocate(trip: OrphanTrip, toSuccess: Boolean)

    /** Remove a trip directory entirely (empty journals only). */
    fun delete(trip: OrphanTrip)

    /** Handle for reading back a journal that was just closed (same directory). */
    fun orphanOf(journal: LiveJournal): OrphanTrip
}

/** A journal being written to while recording. */
class LiveJournal(
    val tripId: String,
    val startMs: Long,
    val pids: List<String>,
    gpsStream: OutputStream,
    obdStream: OutputStream
) {
    private val gpsWriter = RowWriter(gpsStream)
    private val obdWriter = RowWriter(obdStream)
    private var closed = false

    init {
        gpsWriter.write(GPS_HEADER + "\n")
        obdWriter.write(obdHeader(pids) + "\n")
    }

    companion object {
        const val GPS_HEADER = "ts,gps_time,latitude,longitude,altitude,speed_mps,bearing,accuracy_m"
        const val OBD_HEADER_PREFIX = "ts,gps_time,latitude,longitude,altitude"
        fun obdHeader(pids: List<String>): String =
            if (pids.isEmpty()) OBD_HEADER_PREFIX else OBD_HEADER_PREFIX + "," + pids.joinToString(",")
    }

    var approxBytes: Long = 0
        private set

    fun append(row: GpsRow) {
        if (closed) return
        val line = listOf(
            row.ts.toString(),
            row.gpsTime.toString(),
            TripFormats.num(row.latitude, 8),
            TripFormats.num(row.longitude, 8),
            TripFormats.num(row.altitude, 2),
            TripFormats.num(row.speedMps, 3),
            TripFormats.num(row.bearing, 2),
            TripFormats.num(row.accuracyM, 2)
        ).joinToString(",") + "\n"
        gpsWriter.write(line)
        approxBytes += line.length
    }

    fun append(row: ObdRow) {
        if (closed) return
        val sb = StringBuilder()
        sb.append(row.ts).append(',')
            .append(TripFormats.long(row.gpsTime)).append(',')
            .append(TripFormats.num(row.latitude, 8)).append(',')
            .append(TripFormats.num(row.longitude, 8)).append(',')
            .append(TripFormats.num(row.altitude, 2))
        pids.forEach { hex -> sb.append(',').append(TripFormats.num(row.values[hex], 4)) }
        sb.append('\n')
        obdWriter.write(sb.toString())
        approxBytes += sb.length
    }

    fun close() {
        if (closed) return
        closed = true
        gpsWriter.closeQuietly()
        obdWriter.closeQuietly()
    }
}

/** A recorded (but not yet successfully finalized) trip that can be read back. */
abstract class OrphanTrip(val name: String) {
    abstract fun readText(fileName: String): String?

    fun readProps(): Map<String, String> {
        val props = HashMap<String, String>()
        readText("trip.properties")?.lineSequence()?.forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) props[line.substring(0, idx)] = line.substring(idx + 1)
        }
        return props
    }

    fun pids(): List<String> =
        readProps()["pids"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun startMs(): Long = readProps()["start_ms"]?.toLongOrNull() ?: 0L

    fun readGpsRows(): List<GpsRow> {
        val text = readText("gps.csv") ?: return emptyList()
        val rows = ArrayList<GpsRow>()
        var first = true
        for (line in text.lineSequence()) {
            if (first) { first = false; continue }
            parseGpsLine(line)?.let { rows.add(it) }
        }
        return rows
    }

    fun readObdRows(pids: List<String>): List<ObdRow> {
        val text = readText("obd.csv") ?: return emptyList()
        val rows = ArrayList<ObdRow>()
        var first = true
        for (line in text.lineSequence()) {
            if (first) { first = false; continue }
            parseObdLine(line, pids)?.let { rows.add(it) }
        }
        return rows
    }

    fun isEmpty(): Boolean {
        val gps = readText("gps.csv") ?: return true
        val obd = readText("obd.csv") ?: ""
        return gps.length <= LiveJournal.GPS_HEADER.length + 2 &&
            obd.length <= LiveJournal.obdHeader(pids()).length + 2
    }
}

internal fun parseGpsLine(line: String): GpsRow? = try {
    val c = line.split(',')
    GpsRow(
        ts = c[0].toLong(),
        gpsTime = c[1].toLong(),
        latitude = c[2].toDouble(),
        longitude = c[3].toDouble(),
        altitude = c[4].toDouble(),
        speedMps = c[5].toFloatOrNull() ?: 0f,
        bearing = c[6].toFloatOrNull() ?: 0f,
        accuracyM = c[7].toFloatOrNull() ?: 0f
    )
} catch (_: Exception) {
    null // truncated line from an abrupt stop
}

internal fun parseObdLine(line: String, pids: List<String>): ObdRow? = try {
    val c = line.split(',')
    val values = HashMap<String, Double?>()
    pids.forEachIndexed { i, hex -> values[hex] = c.getOrNull(5 + i)?.toDoubleOrNull() }
    ObdRow(
        ts = c[0].toLong(),
        gpsTime = c.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 },
        latitude = c.getOrNull(2)?.toDoubleOrNull(),
        longitude = c.getOrNull(3)?.toDoubleOrNull(),
        altitude = c.getOrNull(4)?.toDoubleOrNull(),
        values = values
    )
} catch (_: Exception) {
    null // truncated line
}

/** java.io.File based journal store (app storage or any plain directory). */
class FileJournalStore(private val root: File) : JournalStore {

    private val pendingRoot get() = File(root, "pending").apply { mkdirs() }
    private val successRoot get() = File(root, "success").apply { mkdirs() }
    private val failedRoot get() = File(root, "failed").apply { mkdirs() }

    override fun createTrip(tripId: String, startMs: Long, pids: List<String>): LiveJournal {
        val dir = File(pendingRoot, tripId)
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create ${dir.absolutePath}")
        File(dir, "trip.properties").writeText(
            buildString {
                append("trip_id=").append(tripId).append('\n')
                append("start_ms=").append(startMs).append('\n')
                append("pids=").append(pids.joinToString(",")).append('\n')
            }
        )
        return LiveJournal(
            tripId, startMs, pids,
            BufferedOutputStream(FileOutputStream(File(dir, "gps.csv"), false), 4096),
            BufferedOutputStream(FileOutputStream(File(dir, "obd.csv"), false), 4096)
        )
    }

    override fun unfinishedTrips(): List<OrphanTrip> {
        val trips = ArrayList<OrphanTrip>()
        for (sub in listOf(pendingRoot, failedRoot)) {
            sub.listFiles { f -> f.isDirectory && File(f, "trip.properties").isFile }
                ?.sortedBy { it.name }
                ?.forEach { trips.add(FileOrphanTrip(it)) }
        }
        return trips
    }

    override fun relocate(trip: OrphanTrip, toSuccess: Boolean) {
        val source = (trip as FileOrphanTrip).dir
        val target = File(if (toSuccess) successRoot else failedRoot, source.name)
        if (target.exists()) target.deleteRecursively()
        source.copyRecursively(target, overwrite = true)
        source.deleteRecursively()
    }

    override fun delete(trip: OrphanTrip) {
        (trip as FileOrphanTrip).dir.deleteRecursively()
    }

    override fun orphanOf(journal: LiveJournal): OrphanTrip =
        FileOrphanTrip(File(pendingRoot, journal.tripId))
}

class FileOrphanTrip(val dir: File) : OrphanTrip(dir.name) {
    override fun readText(fileName: String): String? =
        File(dir, fileName).takeIf { it.isFile }?.readText()
}

/** Buffered row writer that flushes every line so little is lost on a crash. */
private class RowWriter(private val stream: OutputStream) {
    private val buffer = BufferedOutputStream(stream, 4096)

    fun write(text: String) {
        buffer.write(text.toByteArray(Charsets.UTF_8))
        buffer.flush()
    }

    fun closeQuietly() {
        try {
            buffer.flush()
            buffer.close()
        } catch (_: Exception) {
        }
    }
}
