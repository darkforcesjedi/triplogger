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

    /** Categories that are geometry context, not a place worth naming. */
    private val ANONYMOUS_CATEGORIES = setOf("highway", "place", "boundary", "waterway", "natural", "landuse")

    /** Address keys that carry a business/facility name. */
    private val POI_ADDRESS_KEYS = listOf(
        "amenity", "shop", "office", "leisure", "healthcare", "tourism", "craft", "industrial",
        "railway", "aeroway", "man_made"
    )

    data class Place(val timezone: String?, val name: String?)

    /**
     * Pure response parser (unit-tested without network). zoom=18 responses
     * reach building/POI level: the top-level `name` (or the amenity/shop
     * address key) is the business name, and the address carries house
     * number + road - the detail needed for mileage-trust logs.
     */
    internal fun parse(json: String): Place? = try {
        val o = JSONObject(json)
        val displayName = o.optString("display_name").takeIf { it.isNotEmpty() } ?: return null
        val address = o.optJSONObject("address")
        val category = o.optString("category")
        val rawName = o.optString("name").takeIf { it.isNotEmpty() }
        val poiName = rawName?.takeIf { category.isEmpty() || category !in ANONYMOUS_CATEGORIES }
            ?: POI_ADDRESS_KEYS.firstNotNullOfOrNull { key ->
                address?.optString(key)?.takeIf { it.isNotEmpty() }
            }
        Place(o.optString("timezone").takeIf { it.isNotEmpty() }, compose(poiName, address, displayName))
    } catch (_: Exception) {
        null
    }

    /**
     * Compact one-line place: "B-Fresh, 261 19th Street Northwest, Atlanta,
     * 30363". Falls back to the full display name when no usable parts.
     */
    internal fun compose(poiName: String?, address: JSONObject?, displayName: String): String {
        fun p(vararg keys: String) = keys.firstNotNullOfOrNull { address?.optString(it)?.takeIf { v -> v.isNotEmpty() } }
        val street = listOfNotNull(p("house_number"), p("road")).joinToString(" ").takeIf { it.isNotEmpty() }
        val locality = p("city", "town", "village", "municipality", "county")
        val parts = listOfNotNull(
            poiName?.takeUnless { it == street || it == locality },
            street, locality, p("postcode")
        ).distinct()
        return parts.joinToString(", ").takeIf { it.isNotEmpty() } ?: displayName
    }

    /** Blocking network call - run on Dispatchers.IO. Null on any failure. */
    fun lookup(latitude: Double, longitude: Double): Place? = try {
        val url = URL(
            "$ENDPOINT?format=jsonv2&lat=%s&lon=%s&zoom=18&addressdetails=1".format(
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
