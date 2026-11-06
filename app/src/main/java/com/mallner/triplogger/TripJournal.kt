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
    val accuracyM: Float,
    /** Minutes east of UTC when the row was recorded (null in legacy journals). */
    val tzOffsetMin: Int? = null
)

data class ObdRow(
    val ts: Long,
    val gpsTime: Long?,         // most recent GPS fix at poll time (null before first fix)
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val values: Map<String, Double?>,  // pid hex -> value (null = no response this cycle)
    /** Minutes east of UTC when the row was recorded (null in legacy journals). */
    val tzOffsetMin: Int? = null
)

/**
 * Crash-safe on-disk buffer for trips, inside a user-selectable directory with
 * the layout:
 *
 *   <root>/pending/<tripId>/trip.properties, gps-000.csv, obd-000.csv, ...   (recording)
 *   <root>/archive/<tripId>/...                                              (converted, raw record kept)
 *   <root>/failed/<tripId>/...                                               (conversion error)
 *
 * Journals are appended while driving. When a segment grows past the configured
 * max size it is split: recording continues in gps-001.csv/obd-001.csv etc.
 * Splitting is transparent to the trip - all segments are concatenated again
 * during conversion and the trip produces one KMZ track / ODS sheet.
 *
 * When a trip completes it is converted and relocated to archive/ or failed/.
 * Whatever is still in pending/ when the service starts is an interrupted trip
 * and gets recovered (finalized) automatically. The archive is the raw-data
 * record: "Rebuild logs from archive" re-converts it into the output formats.
 */
interface JournalStore {
    fun createTrip(tripId: String, startMs: Long, pids: List<String>): LiveJournal

    /** Every trip found in pending/ and failed/, oldest first. */
    fun unfinishedTrips(): List<OrphanTrip>

    /** Every successfully archived trip, oldest first. */
    fun archivedTrips(): List<OrphanTrip>

    /** Move a trip directory into the archive/ or failed/ subfolder. */
    fun relocate(trip: OrphanTrip, toArchive: Boolean)

    /** Remove a trip directory entirely (empty journals only). */
    fun delete(trip: OrphanTrip)

    /** Handle for reading back a journal that was just closed (same directory). */
    fun orphanOf(journal: LiveJournal): OrphanTrip
}

/**
 * Retention policy applied to archived trip journals on service start.
 * Both rules are off when their threshold is 0.
 */
data class ArchivePolicy(val retentionDays: Int, val minTripSec: Int) {
    fun shouldDelete(startMs: Long, durationMs: Long, nowMs: Long): Boolean {
        if (retentionDays > 0 && startMs > 0 && nowMs - startMs > retentionDays * 86_400_000L) return true
        if (minTripSec > 0 && durationMs < minTripSec * 1000L) return true
        return false
    }
}

/** A journal being written to while recording. */
class LiveJournal(
    val tripId: String,
    val startMs: Long,
    val pids: List<String>,
    private val openSegment: (Int) -> Pair<OutputStream, OutputStream>
) {
    private var gpsWriter: RowWriter
    private var obdWriter: RowWriter
    private var closed = false

    /** 0-based index of the segment currently being written. */
    var segmentCount = 0
        private set

    /** Total bytes written across all segments of this trip. */
    var totalBytes: Long = 0
        private set

    /** Bytes written to the current segment (this is what the split threshold compares against). */
    var approxBytes: Long = 0
        private set

    companion object {
        const val GPS_HEADER = "ts,gps_time,latitude,longitude,altitude,speed_mps,bearing,accuracy_m,tz_offset_min"
        const val OBD_HEADER_PREFIX = "ts,gps_time,latitude,longitude,altitude"
        const val TZ_COLUMN = "tz_offset_min"
        fun obdHeader(pids: List<String>): String {
            val pidsPart = if (pids.isEmpty()) "" else "," + pids.joinToString(",")
            return OBD_HEADER_PREFIX + pidsPart + "," + TZ_COLUMN
        }
    }

    init {
        val streams = openSegment(0)
        gpsWriter = RowWriter(streams.first)
        obdWriter = RowWriter(streams.second)
        writeHeaders()
    }

    /**
     * Close the current segment files and continue recording into the next
     * ones. This is *not* a new trip: the trip id, start time and conversion
     * output are unchanged.
     */
    fun split() {
        if (closed) return
        try {
            gpsWriter.closeQuietly()
            obdWriter.closeQuietly()
        } catch (_: Exception) {
        }
        segmentCount++
        val streams = openSegment(segmentCount)
        gpsWriter = RowWriter(streams.first)
        obdWriter = RowWriter(streams.second)
        writeHeaders()
        approxBytes = 0
    }

    private fun writeHeaders() {
        gpsWriter.write(GPS_HEADER + "\n")
        obdWriter.write(obdHeader(pids) + "\n")
    }

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
            TripFormats.num(row.accuracyM, 2),
            (row.tzOffsetMin ?: TripFormats.deviceOffsetMin(row.ts)).toString()
        ).joinToString(",") + "\n"
        gpsWriter.write(line)
        approxBytes += line.length
        totalBytes += line.length
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
        sb.append(',').append(row.tzOffsetMin ?: TripFormats.deviceOffsetMin(row.ts))
        sb.append('\n')
        obdWriter.write(sb.toString())
        approxBytes += sb.length
        totalBytes += sb.length
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

    /** All file names in the trip directory (used to discover segments). */
    abstract fun listFileNames(): List<String>

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

    /** IANA zone id recorded when the trip started (null in legacy journals). */
    fun tzId(): String? = readProps()["tz_id"]?.takeIf { it.isNotEmpty() }

    /** Minutes east of UTC when the trip started (null in legacy journals). */
    fun tzOffsetMin(): Int? = readProps()["tz_offset_min"]?.toIntOrNull()

    /** Span of the recorded data (first to last sample), 0 when unknown. */
    fun durationMs(): Long {
        var min = Long.MAX_VALUE
        var max = 0L
        for (segment in segments("gps")) {
            val text = readText(segment) ?: continue
            for (line in text.lineSequence().drop(1)) {
                val ts = line.substringBefore(',').toLongOrNull() ?: continue
                if (ts in 1 until min) min = ts
                if (ts > max) max = ts
            }
        }
        for (segment in segments("obd")) {
            val text = readText(segment) ?: continue
            for (line in text.lineSequence().drop(1)) {
                val ts = line.substringBefore(',').toLongOrNull() ?: continue
                if (ts in 1 until min) min = ts
                if (ts > max) max = ts
            }
        }
        return if (min == Long.MAX_VALUE || max <= min) 0L else max - min
    }

    /** Segment file names in write order, including the legacy single-file names. */
    protected fun segments(prefix: String): List<String> =
        listFileNames()
            .filter { segmentIndexOf(it, prefix) != null }
            .sortedBy { segmentIndexOf(it, prefix) }

    /** Index of a segment file ("gps.csv" -> 0, "gps-002.csv" -> 2), null when unrelated. */
    private fun segmentIndexOf(fileName: String, prefix: String): Int? {
        if (!fileName.endsWith(".csv")) return null
        val stem = fileName.removeSuffix(".csv")
        if (stem == prefix) return 0 // legacy single-file journal
        if (!stem.startsWith("$prefix-")) return null
        return stem.removePrefix("$prefix-").toIntOrNull()
    }

    fun readGpsRows(): List<GpsRow> {
        val rows = ArrayList<GpsRow>()
        for (segment in segments("gps")) {
            val text = readText(segment) ?: continue
            var first = true
            for (line in text.lineSequence()) {
                if (first) { first = false; continue }
                parseGpsLine(line)?.let { rows.add(it) }
            }
        }
        return rows
    }

    fun readObdRows(pids: List<String>): List<ObdRow> {
        val rows = ArrayList<ObdRow>()
        for (segment in segments("obd")) {
            val text = readText(segment) ?: continue
            var first = true
            for (line in text.lineSequence()) {
                if (first) { first = false; continue }
                parseObdLine(line, pids)?.let { rows.add(it) }
            }
        }
        return rows
    }

    fun isEmpty(): Boolean {
        val headerAllowance = LiveJournal.GPS_HEADER.length + LiveJournal.obdHeader(pids()).length + 8
        var dataBytes = 0
        segments("gps").mapNotNull { readText(it) }.forEach { dataBytes += it.length }
        segments("obd").mapNotNull { readText(it) }.forEach { dataBytes += it.length }
        return dataBytes <= headerAllowance
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
        accuracyM = c[7].toFloatOrNull() ?: 0f,
        tzOffsetMin = c.getOrNull(8)?.toIntOrNull()
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
        values = values,
        tzOffsetMin = c.getOrNull(5 + pids.size)?.toIntOrNull()
    )
} catch (_: Exception) {
    null // truncated line
}

/** java.io.File based journal store (app storage or any plain directory). */
class FileJournalStore(private val root: File) : JournalStore {

    private val pendingRoot get() = File(root, "pending").apply { mkdirs() }
    private val failedRoot get() = File(root, "failed").apply { mkdirs() }

    private fun archiveRoot(): File {
        val archive = File(root, ARCHIVE_NAME)
        val legacy = File(root, "success")
        if (legacy.exists() && !archive.exists()) legacy.renameTo(archive)
        return archive.apply { mkdirs() }
    }

    override fun createTrip(tripId: String, startMs: Long, pids: List<String>): LiveJournal {
        val dir = File(pendingRoot, tripId)
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create ${dir.absolutePath}")
        File(dir, "trip.properties").writeText(
            buildString {
                append("trip_id=").append(tripId).append('\n')
                append("start_ms=").append(startMs).append('\n')
                append("pids=").append(pids.joinToString(",")).append('\n')
                append("tz_id=").append(TripFormats.deviceZoneId()).append('\n')
                append("tz_offset_min=").append(TripFormats.deviceOffsetMin(startMs)).append('\n')
            }
        )
        return LiveJournal(tripId, startMs, pids) { index ->
            Pair(
                BufferedOutputStream(FileOutputStream(File(dir, segmentName("gps", index)), false), 4096),
                BufferedOutputStream(FileOutputStream(File(dir, segmentName("obd", index)), false), 4096)
            )
        }
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

    override fun archivedTrips(): List<OrphanTrip> =
        archiveRoot().listFiles { f -> f.isDirectory && (File(f, "trip.properties").isFile || File(f, "trip.properties.txt").isFile) }
            ?.sortedBy { it.name }
            ?.map { FileOrphanTrip(it) as OrphanTrip }
            ?: emptyList()

    override fun relocate(trip: OrphanTrip, toArchive: Boolean) {
        val source = (trip as FileOrphanTrip).dir
        if (!source.listFiles().orEmpty().any { it.isFile }) {
            source.deleteRecursively()
            return
        }
        val target = File(if (toArchive) archiveRoot() else failedRoot, source.name)
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

    override fun listFileNames(): List<String> = dir.list()?.toList() ?: emptyList()
}

internal fun segmentName(prefix: String, index: Int): String = "$prefix-%03d.csv".format(index)

/** Subfolder holding successfully converted trip journals (formerly "success"). */
internal const val ARCHIVE_NAME = "archive"

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
