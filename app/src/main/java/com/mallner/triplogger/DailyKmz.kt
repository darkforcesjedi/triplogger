package com.mallner.triplogger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Draws the per-trip speed plot PNG. Dataset1 = OBD speed (red), Dataset2 = GPS speed (blue). */
interface PlotRenderer {
    fun renderPng(label: String, speedUnit: String, obdSpeeds: List<Pair<Long, Float>>, gpsSpeeds: List<Pair<Long, Float>>): ByteArray
}

data class TrackPoint(val gpsTimeMs: Long, val lon: Double, val lat: Double, val alt: Double)

data class TripTrack(
    /** Unique id embedded in the KML ("yyyy-MM-dd HH:mm:ss"), used to make merging idempotent. */
    val trackId: String,
    val label: String,           // "yyyy-MM-dd HH.mm.ss"
    val speedUnit: String,       // display unit of the speed series ("mph" or "km/h")
    val points: List<TrackPoint>,
    val obdSpeeds: List<Pair<Long, Float>>,
    val gpsSpeeds: List<Pair<Long, Float>>
)

/**
 * Builds Google-Earth KMZ files that are structurally identical to what the
 * Python TorqueLogs converter produces: one "<date>.kml" document with one
 * gx:Track placemark per trip plus one "<label>.png" speed plot per trip.
 *
 * Daily files accumulate trips: merging appends new placemarks into the
 * existing KML and copies the existing PNGs across. Color assignment continues
 * the converter's ColorList cycle based on how many placemarks already exist.
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

        fun placemarkXml(label: String, trackId: String, color: String, points: List<TrackPoint>): String {
            val sb = StringBuilder()
            sb.append("  <Placemark>\n")
            sb.append("    <name>" + label + "</name>\n")
            sb.append("    <description><![CDATA[<img src=\"" + label + ".png\">]]></description>\n")
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
                sb.append("      <when>" + TripFormats.kmlWhen(p.gpsTimeMs) + "</when>\n")
                sb.append("      <gx:coord>" + dtostr(p.lon) + " " + dtostr(p.lat) + " " + dtostr(p.alt) + "</gx:coord>\n")
            }
            sb.append("    </gx:Track>\n")
            sb.append("  </Placemark>\n")
            return sb.toString()
        }

        /** Shortest round-trip decimal string (KML does not like fixed padding). */
        fun dtostr(value: Double): String = if (value.isNaN() || value.isInfinite()) "0" else value.toString()

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

        var kml = existingKml
        var placemarkCount = existingKml?.let { Regex("<Placemark>").findAll(it).count() } ?: 0
        val skipIds = existingKml?.let { Regex("gx:Track id=\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toSet() } ?: emptySet()

        for (trip in trips) {
            if (trip.trackId in skipIds) continue  // already merged (recovery/idempotency)
            if (trip.points.isEmpty() && trip.obdSpeeds.isEmpty() && trip.gpsSpeeds.isEmpty()) continue
            val color = colorList[placemarkCount % colorList.size]
            val placemark = placemarkXml(trip.label, trip.trackId, color, trip.points)
            kml = if (kml == null) {
                KML_HEADER + placemark + KML_FOOTER
            } else {
                val idx = kml.lastIndexOf("</Document>")
                if (idx < 0) KML_HEADER + placemark + KML_FOOTER
                else kml.substring(0, idx) + placemark + KML_FOOTER
            }
            placemarkCount++

            val png = renderer.renderPng(trip.label, trip.speedUnit, trip.obdSpeeds, trip.gpsSpeeds)
            existingEntries["${trip.label}.png"] = png
        }

        val finalKml = kml ?: (KML_HEADER + KML_FOOTER)

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
