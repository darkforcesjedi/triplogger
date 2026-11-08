package com.mallner.triplogger

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Reverse geocoding via OpenStreetMap Nominatim: coordinates → timezone name
 * + human-readable placename, in one call. Keyless and free under the OSM
 * fair-use policy, which we honor: one request per endpoint per trip
 * (typically 2 per trip), sequential with a 1.1s gap, descriptive
 * User-Agent, attribution shown in Settings.
 *
 * The user can switch lookups off entirely (Settings → GPS → Look Up
 * Placenames); a failed or disabled lookup just leaves the metadata fields
 * empty and the Trips detail sheet falls back to raw coordinates.
 */
object NominatimClient {

    private const val ENDPOINT = "https://nominatim.openstreetmap.org/reverse"
    private const val USER_AGENT = "TripLogger/1.2 (github.com/darkforcesjedi/triplogger)"
    private const val TIMEOUT_MS = 10_000
    /** Nominatim usage policy: at most 1 request per second. */
    const val POLICY_DELAY_MS = 1_100L

    data class Place(val timezone: String?, val name: String?)

    /** Pure response parser (unit-tested without network). */
    internal fun parse(json: String): Place? = try {
        val o = JSONObject(json)
        val name = o.optString("display_name").takeIf { it.isNotEmpty() }
            ?.let { shorten(it, o.optJSONObject("address")) } ?: return null
        Place(o.optString("timezone").takeIf { it.isNotEmpty() }, name)
    } catch (_: Exception) {
        null
    }

    /**
     * Prefer the tidy address parts over the verbose display_name:
     * "Atlanta, GA" style rather than "Pryor St SW, Neighborhood, City, ...".
     */
    internal fun shorten(displayName: String, address: JSONObject?): String {
        address ?: return displayName
        fun p(vararg keys: String) = keys.firstNotNullOfOrNull { address.optString(it).takeIf { v -> v.isNotEmpty() } }
        val city = p("city", "town", "village", "municipality", "county")
        val region = p("state", "province", "region")
        val postcode = p("postcode")
        return listOfNotNull(city, region, postcode).joinToString(", ").takeIf { it.isNotEmpty() }
            ?: displayName.substringBefore(',')
    }

    /** Blocking network call - run on Dispatchers.IO. Null on any failure. */
    fun lookup(latitude: Double, longitude: Double): Place? = try {
        val url = URL(
            "$ENDPOINT?format=jsonv2&lat=%s&lon=%s&zoom=12&addressdetails=1".format(
                "%.6f".format(latitude), "%.6f".format(longitude)
            )
        )
        (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }.inputStream.bufferedReader().use { reader ->
            parse(reader.readText())
        }
    } catch (e: Exception) {
        null
    }
}
