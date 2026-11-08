package com.mallner.triplogger

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Storage factories shared by the service and the Trips tab actions. */
object Storage {

    fun journalStore(context: Context, settings: AppSettings): JournalStore? {
        if (settings.tempLocationUri.isEmpty()) return null
        return runCatching { SafJournalStore(context, Uri.parse(settings.tempLocationUri)) }.getOrNull()
    }

    /** Output folder (KMZ/ODS); falls back to the legacy combined folder. */
    fun outputStorage(context: Context, settings: AppSettings, uri: String): TripStorage? {
        val effective = uri.ifEmpty { settings.storageLocationUri }
        if (effective.isEmpty()) return null
        return runCatching { SafTreeStorage(context, Uri.parse(effective)) }.getOrNull()
    }
}

enum class TripSection { ARCHIVE, PENDING, FAILED }

/** One row of the Trips tab, built purely from journal metadata (no CSV reads). */
data class TripEntry(
    val name: String,
    val section: TripSection,
    val startMs: Long,
    val endMs: Long,
    val entries: Int,
    val distanceM: Double,
    val maxSpeedMps: Float,
    val segments: Int,
    val title: String?,
    val trigger: String?,
    val tzId: String?,
    val tzName: String?,
    val placeStart: String?,
    val placeEnd: String?,
    val converted: Boolean,
    val kmzDay: String?,
    val odsFile: String?,
    val statusReason: String?,
    val appVersionName: String?
) {
    val durationMs: Long get() = if (endMs > startMs) endMs - startMs else 0L
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: if (startMs > 0) SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(startMs)) else name

    /** Zone abbreviation for display ("EDT"), derived from the recorded zone. */
    val tzAbbrev: String?
        get() = (tzName ?: tzId)?.let { id ->
            runCatching {
                val tz = java.util.TimeZone.getTimeZone(id)
                tz.getDisplayName(
                    tz.useDaylightTime() && tz.inDaylightTime(Date(startMs)),
                    java.util.TimeZone.SHORT,
                    Locale.US
                )
            }.getOrNull()
        }

    /** Version name; numeric values are the interim versionCode field (pre-1.2). */
    val loggedWith: String? = appVersionName?.takeUnless { it.all(Char::isDigit) }
}

/**
 * Reads the journal store for the Trips tab and performs its actions
 * (rename, discard, re-run post-processing, placename lookup, clear failed).
 * All work happens on IO; SAF permissions are held by the app process, so
 * the UI can act without going through the service.
 */
class TripsRepository(private val context: Context) {

    private val settingsRepository = AppSettingsRepository(context)

    private suspend fun snapshot() = settingsRepository.appSettingsFlow.first()

    suspend fun load(): List<TripEntry> = withContext(Dispatchers.IO) {
        val settings = snapshot()
        val store = Storage.journalStore(context, settings) ?: return@withContext emptyList()
        val archive = store.archivedTrips().map { it to TripSection.ARCHIVE }
        val pending = store.pendingTrips().map { it to TripSection.PENDING }
        val failed = store.failedTrips().map { it to TripSection.FAILED }
        (archive + pending + failed).map { (trip, section) -> toEntry(trip, section) }
            .sortedByDescending { it.startMs }
    }

    private fun toEntry(trip: OrphanTrip, section: TripSection): TripEntry {
        val props = trip.readProps()
        return TripEntry(
            name = trip.name,
            section = section,
            startMs = trip.startMs(),
            endMs = trip.endMs(),
            entries = props["entries"]?.toIntOrNull() ?: 0,
            distanceM = trip.distanceM(),
            maxSpeedMps = trip.maxSpeedMps(),
            segments = trip.segmentsCount(),
            title = trip.title(),
            trigger = trip.trigger(),
            tzId = trip.tzId(),
            tzName = trip.tzName(),
            placeStart = trip.placeStart(),
            placeEnd = trip.placeEnd(),
            converted = trip.isConverted(),
            kmzDay = trip.kmzDay(),
            odsFile = trip.odsFile(),
            statusReason = trip.statusReason(),
            appVersionName = trip.appVersionName()
        )
    }

    /**
     * Set/clear the trip title: persisted in trip.properties and, when the
     * trip was converted, reflected in the KMZ placemark name and chart
     * caption via an in-place edit (matched by the stable track id, so no
     * re-processing and no duplicate tracks).
     */
    suspend fun setTitle(entry: TripEntry, title: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = snapshot()
            val store = Storage.journalStore(context, settings)
                ?: error("Temporary storage unavailable")
            val trip = findTrip(store, entry.name) ?: error("Trip ${entry.name} not found")
            trip.mergeProps(mapOf("title" to title))

            val day = trip.kmzDay()
            if (day != null) {
                Storage.outputStorage(context, settings, settings.kmzLocationUri)?.let { kmz ->
                    runCatching {
                        val bytes = kmz.read("$day.kmz") ?: return@runCatching
                        val edited = DailyKmz.renamePlacemark(
                            bytes,
                            listOf(trip.trackId(), trip.legacyTrackId()),
                            title?.takeIf { it.isNotBlank() }
                        ) ?: return@runCatching
                        kmz.write("$day.kmz", edited)
                    }
                }
            }
            ActivityLog.log("trip_renamed", entry.name, "title" to (title ?: ""))
        }
    }

    /** Archive → Failed ("Discard"): recoverable, visible, clearable. */
    suspend fun discardToFailed(entry: TripEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = snapshot()
            val store = Storage.journalStore(context, settings)
                ?: error("Temporary storage unavailable")
            findTrip(store, entry.name)?.let { store.relocate(it, toArchive = false) }
                ?: error("Trip ${entry.name} not found")
            store.failedTrips().firstOrNull { it.name == entry.name }
                ?.mergeProps(mapOf("status_reason" to "discarded by user"))
            ActivityLog.log("trip_discarded_manual", entry.name)
        }
    }

    /**
     * Failed → re-run post-processing. Explicit user action overrides the
     * "discard below N entries" threshold; success moves the journal back
     * to archive/ (status_reason cleared by the finalizer).
     */
    suspend fun rerun(entry: TripEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = snapshot()
            val store = Storage.journalStore(context, settings)
                ?: error("Temporary storage unavailable")
            val trip = findTrip(store, entry.name) ?: error("Trip ${entry.name} not found")
            val kmzStorage = if (settings.generateKmz) Storage.outputStorage(context, settings, settings.kmzLocationUri) else null
            val odsStorage = if (settings.saveOds) Storage.outputStorage(context, settings, settings.odsLocationUri) else null

            val result = TripFinalizer.finalize(
                trip,
                FinalizeSettings(
                    pidHexes = settings.obdSelectedPids,
                    generateKmz = settings.generateKmz,
                    saveOds = settings.saveOds,
                    usUnits = settings.usUnits,
                    appVersionName = BuildConfig.VERSION_NAME
                ),
                kmzStorage,
                odsStorage,
                AndroidPlotRenderer(context)
            )
            when (result) {
                is FinalizeResult.Completed -> {
                    if (entry.section != TripSection.ARCHIVE) store.relocate(trip, toArchive = true)
                    ActivityLog.log("trip_reconverted", entry.name)
                }
                is FinalizeResult.Failed -> {
                    store.failedTrips().firstOrNull { it.name == entry.name }
                        ?.mergeProps(mapOf("status_reason" to (result.error.message ?: "conversion failed")))
                    error(result.error.message ?: "conversion failed")
                }
                is FinalizeResult.Skipped -> error(result.reason)
            }
        }
    }

    /** Empty the Failed / discarded section for good. */
    suspend fun clearFailed(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = snapshot()
            val store = Storage.journalStore(context, settings) ?: return@runCatching 0
            val failed = store.failedTrips()
            failed.forEach { store.delete(it) }
            ActivityLog.log("failed_cleared", "${failed.size} trips")
            failed.size
        }
    }

    /**
     * Nominatim lookup for a trip's start/end points. Runs when the settings
     * toggle is on (automatic, after conversion) and on demand from the Trips
     * tab (explicit "Look up", works even with the toggle off).
     */
    suspend fun lookUpPlaces(tripName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = snapshot()
            val store = Storage.journalStore(context, settings)
                ?: error("Temporary storage unavailable")
            val trip = findTrip(store, tripName) ?: error("Trip $tripName not found")
            if (!resolvePlaces(store, trip)) error("Lookup failed")
        }
    }

    /**
     * Bulk placename resolution across archived trips ("Apply to archived
     * trips"). [untaggedOnly] skips trips that already have a placename;
     * progress reports per trip. Nominatim rate limits apply - two requests
     * per trip at one per second, hence "this may take a while".
     */
    suspend fun lookUpAll(untaggedOnly: Boolean, onProgress: (Int, Int, String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val settings = snapshot()
            val store = Storage.journalStore(context, settings) ?: return@withContext 0
            val trips = store.archivedTrips()
                .filter { !untaggedOnly || it.placeStart().isNullOrEmpty() }
            var done = 0
            var resolved = 0
            for (trip in trips) {
                val label = trip.title() ?: trip.name
                onProgress(done, trips.size, label)
                runCatching { if (resolvePlaces(store, trip)) resolved++ }
                done++
            }
            onProgress(done, trips.size, "")
            ActivityLog.log("places_lookup_all", "$resolved/${trips.size} trips", "scope" to if (untaggedOnly) "untagged" else "all")
            resolved
        }

    /** Start/end coordinates (for the detail sheet) of a trip, if recorded. */
    suspend fun endpoints(tripName: String): Pair<Pair<Double, Double>?, Pair<Double, Double>?> =
        withContext(Dispatchers.IO) {
            val settings = snapshot()
            val store = Storage.journalStore(context, settings) ?: return@withContext null to null
            val trip = findTrip(store, tripName) ?: return@withContext null to null
            val points = trip.readGpsRows().filter { !it.latitude.isNaN() && !it.longitude.isNaN() && it.latitude != 0.0 }
            points.firstOrNull()?.let { Pair(it.latitude, it.longitude) } to
                points.lastOrNull()?.let { Pair(it.latitude, it.longitude) }
        }

    /** Two Nominatim calls (start & end, policy-delayed) + metadata persist. */
    private fun resolvePlaces(store: JournalStore, trip: OrphanTrip): Boolean {
        val points = trip.readGpsRows().filter { !it.latitude.isNaN() && !it.longitude.isNaN() && it.latitude != 0.0 }
        val start = points.firstOrNull() ?: return false
        val end = points.last()

        val startPlace = NominatimClient.lookup(start.latitude, start.longitude) ?: return false
        Thread.sleep(NominatimClient.POLICY_DELAY_MS)
        val endPlace = NominatimClient.lookup(end.latitude, end.longitude) ?: startPlace

        val updates = linkedMapOf(
            "place_start" to startPlace.name,
            "place_end" to endPlace.name
        )
        startPlace.timezone?.let { updates["tz_name"] = it }
        trip.mergeProps(updates)
        ActivityLog.log("places_resolved", trip.name, "start" to (startPlace.name ?: ""))
        return true
    }

    private fun findTrip(store: JournalStore, name: String): OrphanTrip? =
        store.archivedTrips().firstOrNull { it.name == name }
            ?: store.unfinishedTrips().firstOrNull { it.name == name }
}
