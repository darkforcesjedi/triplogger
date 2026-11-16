package com.mallner.triplogger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Draws the per-trip speed plot PNG. Dataset1 = OBD speed (red), Dataset2 = GPS speed (blue). */
interface PlotRenderer {
    /**
     * @param speedUnit    label unit for the y axis ("mph" or "km/h")
     * @param tzOffsetMin  minutes east of UTC the trip started in; axis labels show
     *                     the wall-clock time actually in effect at each instant
     * @param tzChanges    time + new offset (minutes east of UTC) at each time zone /
     *                     DST change, marked with a dashed gridline (may be empty)
     * @param dateChanges  time + new "MM-dd" at each local calendar-day change,
     *                     marked with a dotted gridline (may be empty)
     */
    fun renderPng(label: String, speedUnit: String, tzOffsetMin: Int?, tzChanges: List<Pair<Long, Int>>, dateChanges: List<Pair<Long, String>>, obdSpeeds: List<Pair<Long, Float>>, gpsSpeeds: List<Pair<Long, Float>>): ByteArray
}

data class TrackPoint(val gpsTimeMs: Long, val lon: Double, val lat: Double, val alt: Double, val tzOffsetMin: Int? = null)

data class TripTrack(
    /**
     * Unique id embedded in the KML ("t" + start epoch millis), used to make
     * merging idempotent. v1.1 and earlier derived it from the wall-clock
     * label, which can collide across DST fall-back / time-zone crossings;
     * [legacyTrackId] keeps those placemarks replaceable.
     */
    val trackId: String,
    val label: String,           // "yyyy-MM-dd HH.mm.ss"
    val speedUnit: String,       // display unit of the speed series ("mph" or "km/h")
    val points: List<TrackPoint>,
    val obdSpeeds: List<Pair<Long, Float>>,
    val gpsSpeeds: List<Pair<Long, Float>>,
    /** Offset changes during the trip (time, new minutes east of UTC) for the plot. */
    val tzChanges: List<Pair<Long, Int>> = emptyList(),
    /** Local calendar-day changes during the trip (time, new "MM-dd") for the plot. */
    val dateChanges: List<Pair<Long, String>> = emptyList(),
    /** Minutes east of UTC the trip started in (plot axis labels). */
    val tzOffsetMin: Int? = null,
    /** User title: carries the placemark name and the chart caption (null = label). */
    val title: String? = null,
    /** v1.1 label-derived id of this same trip, when converting a legacy placemark. */
    val legacyTrackId: String? = null,
    /** Annotate-mode confirmed stops; become pins with durations along the track. */
    val stops: List<StopEvent> = emptyList()
)

/**
 * Builds Google-Earth KMZ files that are structurally identical to what the
 * Python TorqueLogs converter produces: one "<date>.kml" document with one
 * gx:Track placemark per trip plus one "<label>.png" speed plot per trip.
 * Trips with stop annotations are grouped in a Folder together with their
 * "Stopped for <duration>" pins, so Google Earth nests the stops under the
 * trip; stop-free trips stay flat placemarks.
 *
 * Placemarks are kept in chronological order (by the UTC instant of their
 * first <when>, offset-aware). Re-processing a trip (crash recovery, rebuild
 * from archive) *replaces* the placemark with the same gx:Track id instead of
 * appending a duplicate, and its "<label>.png" entry is overwritten, so the
 * regenerated trip wins while every other trip in the day is untouched.
 */
class DailyKmz(
    val colorList: List<String> = DEFAULT_COLORS,
    val renderer: PlotRenderer
) {
    companion object {
        val DEFAULT_COLORS = listOf("FF1400FF", "FF14F0FF", "FF78FF00", "FFFF7800", "FF1478FF", "FFFF78F0")

        private const val KML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<kml xmlns = \"http://www.opengis.net/kml/2.2\" xmlns:gx = \"http://www.google.com/kml/ext/2.2\">\n" +
            "<Document>\n"
        private const val KML_FOOTER = "</Document>\n</kml>"

        /**
         * Name and image caption carry the UTC offset the trip started in
         * ("2026-11-05 21.43.50 UTC-05:00"); the id and the PNG file name stay
         * exactly as before so existing tracks keep matching on rebuild.
         * With a user [title], the placemark name and the caption's first line
         * are the title instead (timestamp stays below it).
         */
        fun placemarkXml(label: String, trackId: String, color: String, points: List<TrackPoint>, title: String? = null): String {
            val name = tripName(label, points, title)
            val timestamp = points.firstOrNull()?.let { TripFormats.kmlWhen(it.gpsTimeMs, it.tzOffsetMin) + "<br/>" } ?: ""
            val caption = title?.takeIf { it.isNotBlank() }?.let { escXml(it) + "<br/>" }.orEmpty() + timestamp
            val sb = StringBuilder()
            sb.append("  <Placemark>\n")
            sb.append("    <name>" + escXml(name) + "</name>\n")
            sb.append("    <description><![CDATA[" + caption + "<img src=\"" + label + ".png\">]]></description>\n")
            sb.append("    <Style>\n")
            sb.append("      <IconStyle><Icon/></IconStyle>\n")
            sb.append("      <LabelStyle><scale>0</scale></LabelStyle>\n")
            sb.append("      <LineStyle>\n")
            sb.append("        <color>" + color + "</color>\n")
            sb.append("        <width>5</width>\n")
            sb.append("      </LineStyle>\n")
            sb.append("    </Style>\n")
            sb.append("    <gx:Track id=\"" + trackId + "\">\n")
            sb.append("      <altitudeMode>clampToGround</altitudeMode>\n")
            for (p in points) {
                sb.append("      <when>" + TripFormats.kmlWhen(p.gpsTimeMs, p.tzOffsetMin) + "</when>\n")
                sb.append("      <gx:coord>" + dtostr(p.lon) + " " + dtostr(p.lat) + " " + dtostr(p.alt) + "</gx:coord>\n")
            }
            sb.append("    </gx:Track>\n")
            sb.append("  </Placemark>\n")
            return sb.toString()
        }

        /** Placemark (and folder) label: title, or the label plus start-of-trip UTC offset. */
        internal fun tripName(label: String, points: List<TrackPoint>, title: String?): String {
            val offsetMin = points.firstNotNullOfOrNull { it.tzOffsetMin }
            return title?.takeIf { it.isNotBlank() }
                ?: label + (offsetMin?.let { " " + TripFormats.utcLabel(it) } ?: "")
        }

        /** Shortest round-trip decimal string (KML does not like fixed padding). */
        fun dtostr(value: Double): String = if (value.isNaN() || value.isInfinite()) "0" else value.toString()

        /**
         * Pin key embedding the owning track id, so rebuilding a trip
         * replaces (and re-deriving without the stop drops) its own pins.
         */
        internal fun pinId(trackId: String, startMs: Long): String = "$trackId-stop$startMs"

        /**
         * One stop as a Point placemark grouped with its trip in a Folder:
         * plain default pin (no icon href keeps the KMZ portable), name carries
         * the dwell duration and the balloon the arrival/departure wall times.
         * Null for stops with no fix during the dwell.
         */
        internal fun stopPinXml(trackId: String, stop: StopEvent, tzOffsetMin: Int?): String? {
            val lat = stop.latitude ?: return null
            val lon = stop.longitude ?: return null
            val duration = AppNotificationManager.formatChipDuration(stop.durationSec * 1000)
            val start = TripFormats.odsTime(stop.startMs, tzOffsetMin)
            val end = TripFormats.odsTime(stop.endMs, tzOffsetMin)
            val sb = StringBuilder()
            sb.append("  <Placemark id=\"" + pinId(trackId, stop.startMs) + "\">\n")
            sb.append("    <name>" + escXml("Stopped for $duration") + "</name>\n")
            sb.append("    <description><![CDATA[Arrive: $start<br/>Depart: $end]]></description>\n")
            sb.append("    <TimeStamp><when>" +
                TripFormats.kmlWhen(stop.startMs, tzOffsetMin) + "</when></TimeStamp>\n")
            sb.append("    <Style><IconStyle><scale>0.8</scale></IconStyle></Style>\n")
            sb.append("    <Point><altitudeMode>clampToGround</altitudeMode><coordinates>" +
                dtostr(lon) + "," + dtostr(lat) + ",0</coordinates></Point>\n")
            sb.append("  </Placemark>\n")
            return sb.toString()
        }

        /**
         * Group a trip's track placemark with its stop pins so Google Earth
         * nests the pins under the trip in the Places tree. KML has no
         * feature-to-feature hierarchy; Folder is the containment mechanism.
         */
        private fun folderXml(name: String, trackBlock: String, pinBlocks: List<String>): String =
            buildString {
                append("<Folder>\n")
                append("  <name>" + escXml(name) + "</name>\n")
                append(trackBlock)
                pinBlocks.forEach { append(it) }
                append("</Folder>\n")
            }

        private val WHEN_MS = Regex("<when>(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})([-+]\\d{2}:\\d{2})?</when>")

        /** UTC instant of a block's first <when>; naive stamps read as device-local. */
        internal fun firstWhenMs(placemarkBlock: String): Long? {
            val m = WHEN_MS.find(placemarkBlock) ?: return null
            return try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fmt.timeZone = m.groupValues[2].takeIf { it.isNotEmpty() }
                    ?.let { TimeZone.getTimeZone("GMT$it") } ?: TimeZone.getDefault()
                fmt.parse(m.groupValues[1]).time
            } catch (_: Exception) {
                null
            }
        }

        /** Returns the coordinates + track ids in a KMZ (for verification / tests). */
        fun inspect(kmzBytes: ByteArray): Pair<String?, Set<String>> {
            var kml: String? = null
            ZipInputStream(ByteArrayInputStream(kmzBytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".kml")) {
                        kml = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
            val ids = Regex("gx:Track id=\"([^\"]+)\"").findAll(kml ?: "").map { it.groupValues[1] }.toSet()
            return kml to ids
        }

        internal fun escXml(value: String): String = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

        /**
         * Split a KML document into head + feature blocks + tail. A block is a
         * top-level Placemark or Folder (the latter groups a trip's track with
         * its stop pins); blocks are keyed by their gx:Track id when present
         * (a folder keys by the track it contains), else by Placemark id, and
         * unkeyed blocks get positional keys.
         */
        internal fun splitPlacemarks(kml: String): Triple<String, LinkedHashMap<String, String>, String> {
            val blocks = LinkedHashMap<String, String>()
            var pos = 0
            var unkeyed = 0
            var firstBlock = -1
            var endLast = -1
            while (pos < kml.length) {
                val p = kml.indexOf("<Placemark", pos)
                val f = kml.indexOf("<Folder", pos)
                val start = when {
                    p < 0 && f < 0 -> break
                    f in 0 until if (p < 0) Int.MAX_VALUE else p -> f
                    else -> p
                }
                val end = if (start == f) folderEnd(kml, start)
                    else kml.indexOf("</Placemark>", start).takeIf { it >= 0 }?.plus("</Placemark>".length)
                if (end == null) break // malformed tail: keep what we parsed
                val block = kml.substring(start, end) + "\n"
                val id = Regex("gx:Track id=\"([^\"]+)\"").find(block)?.groupValues?.get(1)
                    // stop pins carry their id on the Placemark itself
                    ?: Regex("<Placemark id=\"([^\"]+)\"").find(block)?.groupValues?.get(1)
                    ?: "#unkeyed-${unkeyed++}"
                blocks[id] = block
                if (firstBlock < 0) firstBlock = start
                endLast = end
                pos = end
            }
            if (blocks.isEmpty()) return Triple(KML_HEADER, linkedMapOf(), KML_FOOTER)
            return Triple(kml.substring(0, firstBlock), blocks, kml.substring(endLast))
        }

        /** Index just past the `</Folder>` matching the `<Folder` opening at [start]. */
        private fun folderEnd(kml: String, start: Int): Int? {
            var depth = 1 // the opening tag at [start] itself
            var pos = start
            while (true) {
                val close = kml.indexOf("</Folder>", pos)
                if (close < 0) return null
                val open = kml.indexOf("<Folder", pos + 1)
                if (open in 0 until close) { depth++; pos = open + 7 } else {
                    depth--
                    pos = close + 9
                    if (depth == 0) return pos
                }
            }
        }

        /**
         * Rename a trip's placemark inside an existing KMZ without re-processing:
         * finds the block by any of [trackIds] (new "t…" id first, legacy
         * label-derived id as fallback), rewrites `<name>` and the caption's
         * first CDATA line to [title] (null clears the title, restoring the
         * timestamp-only caption), and migrates the placemark id to
         * [trackIds]`[0]` so future edits match directly.
         *
         * @return new KMZ bytes, or null when no placemark matched (e.g. the
         *         trip was never converted or the file predates it).
         */
        fun renamePlacemark(kmzBytes: ByteArray, trackIds: List<String>, title: String?): ByteArray? {
            val primaryId = trackIds.firstOrNull() ?: return null
            var kmlName: String? = null
            var kml: String? = null
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(kmzBytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".kml")) {
                        kmlName = entry.name
                        kml = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (!entry.name.endsWith("/")) {
                        entries[entry.name] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
            val document = kml ?: return null

            val (head, blocks, tail) = splitPlacemarks(document)
            val matchedId = trackIds.firstOrNull { blocks.containsKey(it) } ?: return null

            val whenLine = Regex("<when>([^<]+)</when>").find(blocks[matchedId]!!)?.groupValues?.get(1) ?: ""
            val imgSrc = Regex("<img src=\"([^\"]+)\">").find(blocks[matchedId]!!)?.groupValues?.get(1) ?: ""
            val label = imgSrc.removeSuffix(".png")
            val utcSuffix = Regex("<name>.* (UTC[-+][0-9:]{5,6})</name>").find(blocks[matchedId]!!)?.groupValues?.get(1)
            val name = title?.takeIf { it.isNotBlank() }
                ?: (label + (utcSuffix?.let { " $it" } ?: ""))
            val caption = (title?.takeIf { it.isNotBlank() }?.let { escXml(it) + "<br/>" } ?: "") +
                (whenLine.takeIf { it.isNotEmpty() }?.let { "$it<br/>" } ?: "")
            // A folder block carries the trip name twice (folder + track);
            // stop pins keep their "Stopped for ..." names.
            var nameSlots = if (blocks[matchedId]!!.startsWith("<Folder")) 2 else 1
            val renamed = blocks[matchedId]!!
                .replace(Regex("<name>.*</name>")) {
                    if (nameSlots-- > 0) "<name>${escXml(name)}</name>" else it.value
                }
                .replaceFirst(
                    Regex("<description><!\\[CDATA\\[.*]]></description>"),
                    "<description><![CDATA[$caption<img src=\"$imgSrc\">]]></description>"
                )
                .replace("gx:Track id=\"$matchedId\"", "gx:Track id=\"$primaryId\"")

            // replace in place, keeping original document order
            val ordered = LinkedHashMap<String, String>()
            for ((id, value) in blocks) {
                if (id == matchedId) ordered[primaryId] = renamed else ordered[id] = value
            }

            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                entries.forEach { (n, bytes) ->
                    zip.putNextEntry(ZipEntry(n))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(kmlName ?: "doc.kml"))
                zip.write((head + ordered.values.joinToString(separator = "") + tail).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            return out.toByteArray()
        }
    }

    /**
     * @param existingKmz current contents of the day's file, or null when none exists yet
     * @param dayLabel    "yyyy-MM-dd" - also the KML entry name stem
     * @return the new KMZ bytes
     */
    fun merge(existingKmz: ByteArray?, dayLabel: String, trips: List<TripTrack>): ByteArray {
        val existingEntries = LinkedHashMap<String, ByteArray>()
        var existingKml: String? = null
        if (existingKmz != null && existingKmz.isNotEmpty()) {
            ZipInputStream(ByteArrayInputStream(existingKmz)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.endsWith(".kml")) {
                        existingKml = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (!name.endsWith("/")) {
                        existingEntries[name] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
        }

        // Split the document into head + placemark blocks + tail; blocks are keyed
        // by gx:Track id when present so a re-processed trip replaces itself.
        var head = KML_HEADER
        var tail = KML_FOOTER
        val blocks = LinkedHashMap<String, String>()
        existingKml?.let { kml ->
            val (h, b, t) = splitPlacemarks(kml)
            if (b.isNotEmpty()) {
                head = h
                tail = t
                blocks.putAll(b)
            }
        }

        for (trip in trips) {
            if (trip.points.isEmpty() && trip.obdSpeeds.isEmpty() && trip.gpsSpeeds.isEmpty()) continue
            // A pre-v1.2 placemark of this same trip (label-derived id) is
            // replaced in place - never left behind as a duplicate.
            val idCandidates = listOfNotNull(trip.trackId, trip.legacyTrackId)
            val previousKey = idCandidates.firstOrNull { blocks.containsKey(it) }
            val previousBlock = previousKey?.let { blocks[it] }
            // Stop pins do not consume palette colors, so counting only track
            // blocks keeps a trip's color stable across rebuilds.
            val color = previousBlock?.let { Regex("<color>([0-9A-Fa-f]+)</color>").find(it)?.groupValues?.get(1) }
                ?: colorList[blocks.keys.count { "-stop" !in it } % colorList.size]
            val block0 = placemarkXml(trip.label, trip.trackId, color, trip.points, trip.title)
            // With stops, group track and pins in a Folder so Google Earth
            // nests them under the trip; without, keep a flat placemark.
            val pinBlocks = trip.stops.mapNotNull { stopPinXml(trip.trackId, it, trip.tzOffsetMin) }
            val block = if (pinBlocks.isEmpty()) block0 else
                folderXml(tripName(trip.label, trip.points, trip.title), block0, pinBlocks)
            if (previousKey != null) {
                if (previousKey == trip.trackId) {
                    blocks[previousKey] = block // same key: in-place, order preserved
                } else {
                    // migrate the legacy id to the new one, keeping document order
                    val migrated = LinkedHashMap<String, String>()
                    for ((id, value) in blocks) {
                        migrated[if (id == previousKey) trip.trackId else id] = if (id == previousKey) block else value
                    }
                    blocks.clear()
                    blocks.putAll(migrated)
                }
            } else {
                blocks[trip.trackId] = block
            }
            // Drop stale flat pins from earlier builds (a rebuild may see a
            // different stop list); the fresh pins live inside the folder.
            for (ownerId in idCandidates) {
                blocks.keys.filter { it.startsWith("$ownerId-stop") }.forEach { blocks.remove(it) }
            }
            existingEntries["${trip.label}.png"] = renderer.renderPng(
                trip.label, trip.speedUnit, trip.tzOffsetMin,
                trip.tzChanges, trip.dateChanges, trip.obdSpeeds, trip.gpsSpeeds
            )
        }

        // chronological order by the actual (offset-aware) UTC instants
        val ordered = blocks.entries.sortedBy { firstWhenMs(it.value) ?: Long.MAX_VALUE }
        val finalKml = head + ordered.joinToString(separator = "") { it.value } + tail

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            existingEntries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("$dayLabel.kml"))
            zip.write(finalKml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
