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
    /** Unique id embedded in the KML ("yyyy-MM-dd HH:mm:ss"), used to make merging idempotent. */
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
    val tzOffsetMin: Int? = null
)

/**
 * Builds Google-Earth KMZ files that are structurally identical to what the
 * Python TorqueLogs converter produces: one "<date>.kml" document with one
 * gx:Track placemark per trip plus one "<label>.png" speed plot per trip.
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
         */
        fun placemarkXml(label: String, trackId: String, color: String, points: List<TrackPoint>): String {
            val offsetMin = points.firstNotNullOfOrNull { it.tzOffsetMin }
            val name = label + (offsetMin?.let { " " + TripFormats.utcLabel(it) } ?: "")
            val caption = points.firstOrNull()?.let { TripFormats.kmlWhen(it.gpsTimeMs, it.tzOffsetMin) + "<br/>" } ?: ""
            val sb = StringBuilder()
            sb.append("  <Placemark>\n")
            sb.append("    <name>" + name + "</name>\n")
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

        /** Shortest round-trip decimal string (KML does not like fixed padding). */
        fun dtostr(value: Double): String = if (value.isNaN() || value.isInfinite()) "0" else value.toString()

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
            val first = kml.indexOf("<Placemark")
            val last = kml.lastIndexOf("</Placemark>")
            if (first >= 0 && last >= 0) {
                head = kml.substring(0, first)
                tail = kml.substring(last + "</Placemark>".length)
                var pos = first
                var unkeyed = 0
                while (true) {
                    val s = kml.indexOf("<Placemark", pos)
                    if (s < 0) break
                    val e = kml.indexOf("</Placemark>", s)
                    if (e < 0) break
                    val block = kml.substring(s, e + "</Placemark>".length) + "\n"
                    val id = Regex("gx:Track id=\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: "#unkeyed-${unkeyed++}"
                    blocks[id] = block
                    pos = e + "</Placemark>".length
                }
            }
        }

        for (trip in trips) {
            if (trip.points.isEmpty() && trip.obdSpeeds.isEmpty() && trip.gpsSpeeds.isEmpty()) continue
            val color = blocks[trip.trackId]?.let { Regex("<color>([0-9A-Fa-f]+)</color>").find(it)?.groupValues?.get(1) }
                ?: colorList[blocks.size % colorList.size]
            blocks[trip.trackId] = placemarkXml(trip.label, trip.trackId, color, trip.points)
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
