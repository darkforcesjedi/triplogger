package com.mallner.triplogger

import android.content.Context
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Storage factories shared by the service and the Trips tab actions. */
object Storage {

    private const val TAG = "Storage"

    /** One metadata store per journal root for the life of the process. */
    private val metadataStores = ConcurrentHashMap<String, TripMetadataStore?>()

    @Volatile
    private var unavailableReported = false

    fun journalStore(context: Context, settings: AppSettings): JournalStore? {
        if (settings.tempLocationUri.isEmpty()) return null
        return runCatching { SafJournalStore(context, Uri.parse(settings.tempLocationUri)) }.getOrNull()
    }

    /**
     * Trip metadata database for the configured journal root: opened lazily
     * at a File inside the SAF tree (primary external storage only). Null
     * when no root is set, the folder is not on primary storage, or the
     * file cannot be opened (corruption; the restore flow arrives later).
     */
    fun metadataStore(context: Context, settings: AppSettings): TripMetadataStore? =
        metadataStore(context, settings.tempLocationUri)

    /** Uri-string form: settings themselves may live IN the store. */
    fun metadataStore(context: Context, uri: String): TripMetadataStore? {
        if (uri.isEmpty()) return null
        metadataStores[uri]?.let { return it }
        return synchronized(metadataStores) {
            metadataStores[uri]?.let { return it }
            val store = runCatching {
                val root = JournalPath.rootDir(uri)
                if (root == null) {
                    Log.w(TAG, "Journal root not resolvable to filesystem storage: $uri")
                    null
                } else {
                    TripMetadataStore(context.applicationContext, root)
                }
            }.onFailure { Log.e(TAG, "Cannot open trip metadata database", it) }.getOrNull()
            if (store != null) {
                metadataStores[uri] = store
            } else if (!unavailableReported) {
                // One activity entry per process: explains an empty Trips tab.
                unavailableReported = true
                ActivityLog.log("db_unavailable", "trip metadata database cannot be opened")
            }
            store
        }
    }

    /** Close and evict the cached store (before replacing the db file). */
    fun dropMetadataStore(uri: String) {
        val removed = metadataStores.remove(uri)
        runCatching { removed?.close() }
    }

    /** Output folder (KMZ/ODS); falls back to the legacy combined folder. */
    fun outputStorage(context: Context, settings: AppSettings, uri: String): TripStorage? {
        val effective = uri.ifEmpty { settings.storageLocationUri }
        if (effective.isEmpty()) return null
        return runCatching { SafTreeStorage(context, Uri.parse(effective)) }.getOrNull()
    }
}

enum class TripSection { ARCHIVE, PENDING, FAILED }

/** One row of the Trips tab, built purely from the metadata DB (no CSV reads). */
data class TripEntry(
    val tripId: String,
    val name: String,
    val section: TripSection,
    val startMs: Long,
    val endMs: Long,
    val entries: Int,
    val distanceM: Double,
    /** Best source label (odo|obd|gps_speed|track) for multi-source trips, null for legacy. */
    val distanceSrc: String?,
    /** All persisted distance estimates (source -> meters), empty for legacy trips. */
    val distanceSources: Map<String, Double> = emptyMap(),
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
    val appVersionName: String?,
    /** Raw journal lifecycle state (present | purged_* | missing). */
    val rawState: String = TripDatabase.RAW_PRESENT
) {
    /** Raw journal files are still on disk (sample-reading actions need this). */
    val hasRawData: Boolean get() = rawState == TripDatabase.RAW_PRESENT

    /** Distance to present: best multi-source estimate, else the legacy value. */
    val bestDistanceM: Double?
        get() = distanceSrc?.let { distanceSources[it] }
            ?: distanceSources[DistanceEstimators.SOURCE_ODO]
            ?: distanceSources[DistanceEstimators.SOURCE_OBD]
            ?: distanceSources[DistanceEstimators.SOURCE_GPS_SPEED]
            ?: distanceSources[DistanceEstimators.SOURCE_TRACK]
            ?: distanceM.takeIf { !it.isNaN() }

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
    val loggedWith: String? get() = displayVersion(appVersionName)

    companion object {
        /**
         * User-facing logged-with version. Trips finalized by interim dev
         * builds stored the numeric versionCode (e.g. "2") - those values are
         * not meaningful to users (hidden until a re-run overwrites them with
         * the version NAME, e.g. "1.1").
         */
        fun displayVersion(raw: String?): String? = raw?.takeUnless { it.isEmpty() || it.all(Char::isDigit) }
    }
}

/**
 * Reads the metadata database for the Trips tab and performs its actions
 * (rename, discard, re-run post-processing, placename lookup, clear failed);
 * journal folders stay the raw-data layer, addressed through the row's
 * folder column. All work happens on IO; SAF permissions are held by the
 * app process, so the UI can act without going through the service.
 */
class TripsRepository(private val context: Context) {

    private val settingsRepository = AppSettingsRepository(context)

    private suspend fun snapshot() = settingsRepository.appSettingsFlow.first()

    suspend fun load(): List<TripEntry> = withContext(Dispatchers.IO) {
        val settings = snapshot()
        val db = JournalSync.ensureSynced(context, settings) ?: return@withContext emptyList()
        val distances = db.metricsIndex("distance_m")
        val maxSpeeds = db.metricsIndex("max_speed_mps")
        val kmzDays = db.outputsIndex("kmz_day")
        val odsFiles = db.outputsIndex("ods")
        db.trips().map { row ->
            toEntry(row, distances[row.id], maxSpeeds[row.id]?.values?.firstOrNull(),
                kmzDays[row.id], odsFiles[row.id])
        }
    }

    /**
     * Filename of the snapshot a restore would offer, or null. Non-null
     * only when a journal is configured, its database cannot be opened,
     * backups/ holds files, AND the newest backup actually contains at
     * least one of the journal folders on disk: never offer a rollback
     * that cannot fix the problem the user is seeing (a folder that was
     * hand-copied into the journal is a merge case, not a backup case).
     */
    suspend fun restoreOffer(): String? = withContext(Dispatchers.IO) {
        val settings = snapshot()
        if (settings.tempLocationUri.isEmpty()) return@withContext null
        if (Storage.metadataStore(context, settings) != null) return@withContext null
        val root = JournalPath.rootDir(settings.tempLocationUri) ?: return@withContext null
        val newest = DbBackup.availableBackups(root).firstOrNull()
        val onDisk = buildSet {
            for (state in listOf(
                TripDatabase.STATE_ARCHIVE, TripDatabase.STATE_PENDING, TripDatabase.STATE_FAILED
            )) {
                java.io.File(root, state).listFiles()?.filter { it.isDirectory }
                    ?.forEach { add(it.name) }
            }
        }
        val covers = newest != null && onDisk.isNotEmpty() && DbBackup.backupCoversAny(newest, onDisk)
        DbBackup.restoreOfferName(newest?.name, onDisk, covers)
    }

    suspend fun restoreBackup(name: String): DbBackup.Outcome = withContext(Dispatchers.IO) {
        DbBackup.restore(context, snapshot().tempLocationUri, name)
    }

    private fun toEntry(
        row: TripMetadataStore.TripRow,
        distanceSources: Map<String, Double>?,
        maxSpeed: Double?,
        kmzName: String?,
        odsFile: String?
    ) = TripEntry(
        tripId = row.id,
        name = row.folder,
        section = when (row.state) {
            TripDatabase.STATE_ARCHIVE -> TripSection.ARCHIVE
            TripDatabase.STATE_FAILED -> TripSection.FAILED
            else -> TripSection.PENDING
        },
        startMs = row.startMs,
        endMs = row.endMs ?: 0L,
        entries = row.entries,
        distanceM = distanceSources?.get(DistanceEstimators.SOURCE_TRACK) ?: Double.NaN,
        distanceSrc = row.distanceSource,
        distanceSources = distanceSources ?: emptyMap(),
        maxSpeedMps = maxSpeed?.toFloat() ?: Float.NaN,
        segments = row.segments,
        title = row.title,
        trigger = row.trigger,
        tzId = row.tzId,
        tzName = row.tzName,
        placeStart = row.placeStart,
        placeEnd = row.placeEnd,
        converted = row.convertedAt != null,
        kmzDay = kmzName?.removeSuffix(".kmz"),
        odsFile = odsFile,
        statusReason = row.statusReason,
        appVersionName = row.appVersion,
        rawState = row.rawState
    )

    private suspend fun metadataStore(): TripMetadataStore? =
        JournalSync.ensureSynced(context, snapshot())

    private suspend fun rowFor(entry: TripEntry): Pair<TripMetadataStore, TripMetadataStore.TripRow> {
        val db = metadataStore() ?: error("Trip metadata database unavailable")
        return Pair(db, db.tripByFolder(entry.name) ?: error("Trip ${entry.name} not in database"))
    }

    /**
     * Set/clear the trip title: stored in the trips row and, when the trip
     * was converted, reflected in the KMZ placemark name and chart caption
     * via an in-place edit (matched by the stable track id, so no
     * re-processing and no duplicate tracks).
     */
    suspend fun setTitle(entry: TripEntry, title: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = snapshot()
            val (db, row) = rowFor(entry)
            val clean = title?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.takeIf { it.isNotEmpty() }
            db.setTitle(row.id, clean)

            val day = db.outputsIndex("kmz_day")[row.id]?.removeSuffix(".kmz")
            if (day != null) {
                Storage.outputStorage(context, settings, settings.kmzLocationUri)?.let { kmz ->
                    runCatching {
                        val bytes = kmz.read("$day.kmz") ?: return@runCatching
                        val edited = DailyKmz.renamePlacemark(
                            bytes,
                            listOf("t${row.startMs}", legacyTrackId(row)),
                            clean
                        ) ?: return@runCatching
                        kmz.write("$day.kmz", edited)
                    }
                }
            }
            ActivityLog.log("trip_renamed", entry.name, "title" to (clean ?: ""))
        }
    }

    /** Archive → Failed ("Discard"): recoverable, visible, clearable. */
    suspend fun discardToFailed(entry: TripEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = snapshot()
            val store = Storage.journalStore(context, settings)
                ?: error("Temporary storage unavailable")
            val (db, row) = rowFor(entry)
            findTrip(store, entry.name)?.let { store.relocate(it, toArchive = false) }
                ?: error("Trip ${entry.name} not found")
            db.relocate(row.id, null, TripDatabase.STATE_FAILED)
            db.setStatusReason(row.id, "discarded by user")
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
            val (db, row) = rowFor(entry)
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
                AndroidPlotRenderer(context),
                meta = db,
                target = row.toTarget()
            )
            when (result) {
                is FinalizeResult.Completed -> {
                    if (entry.section != TripSection.ARCHIVE) {
                        store.relocate(trip, toArchive = true)
                        db.relocate(row.id, null, TripDatabase.STATE_ARCHIVE)
                    }
                    ActivityLog.log("trip_reconverted", entry.name)
                }
                is FinalizeResult.Failed -> {
                    db.setStatusReason(row.id, result.error.message ?: "conversion failed")
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
            val db = metadataStore() ?: return@runCatching 0
            val store = Storage.journalStore(context, settings)
            val rows = db.trips(TripDatabase.STATE_FAILED)
            val folders = store?.failedTrips()?.associateBy { it.name } ?: emptyMap()
            rows.forEach { row ->
                folders[row.folder]?.let { runCatching { store?.delete(it) } }
                db.deleteTrip(row.id)
            }
            ActivityLog.log("failed_cleared", "${rows.size} trips")
            rows.size
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
            val db = metadataStore() ?: error("Trip metadata database unavailable")
            val row = db.tripByFolder(tripName) ?: error("Trip $tripName not in database")
            if (!resolvePlaces(db, row, trip)) error("Lookup failed")
        }
    }

    /**
     * Bulk placename resolution across archived trips ("Apply to archived
     * trips"). [untaggedOnly] skips trips that already have a placename;
     * progress reports per trip. Nominatim rate limits apply - two requests
     * per trip at one per second, hence "this may take a while". Trips whose
     * raw journals were purged are skipped (nothing to resolve from).
     */
    suspend fun lookUpAll(untaggedOnly: Boolean, onProgress: (Int, Int, String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val settings = snapshot()
            val db = JournalSync.ensureSynced(context, settings) ?: return@withContext 0
            val store = Storage.journalStore(context, settings) ?: return@withContext 0
            val folders = store.archivedTrips().associateBy { it.name }
            val rows = db.trips(TripDatabase.STATE_ARCHIVE)
                .filter { it.rawState == TripDatabase.RAW_PRESENT }
                .filter { !untaggedOnly || it.placeStart.isNullOrEmpty() }
            var done = 0
            var resolved = 0
            for (row in rows) {
                val label = row.title ?: row.folder
                onProgress(done, rows.size, label)
                val trip = folders[row.folder]
                runCatching { if (trip != null && resolvePlaces(db, row, trip)) resolved++ }
                done++
            }
            onProgress(done, rows.size, "")
            ActivityLog.log("places_lookup_all", "$resolved/${rows.size} trips", "scope" to if (untaggedOnly) "untagged" else "all")
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
    private fun resolvePlaces(db: TripMetadataStore, row: TripMetadataStore.TripRow, trip: OrphanTrip): Boolean {
        val points = trip.readGpsRows().filter { !it.latitude.isNaN() && !it.longitude.isNaN() && it.latitude != 0.0 }
        val start = points.firstOrNull() ?: return false
        val end = points.last()

        val startPlace = NominatimClient.lookup(start.latitude, start.longitude) ?: return false
        Thread.sleep(NominatimClient.POLICY_DELAY_MS)
        val endPlace = NominatimClient.lookup(end.latitude, end.longitude) ?: startPlace

        db.setPlaces(row.id, startPlace.name, endPlace.name)
        startPlace.timezone?.let { db.setTzName(row.id, it) }
        ActivityLog.log("places_resolved", trip.name, "start" to (startPlace.name ?: ""))
        return true
    }

    private fun findTrip(store: JournalStore, name: String): OrphanTrip? =
        store.archivedTrips().firstOrNull { it.name == name }
            ?: store.unfinishedTrips().firstOrNull { it.name == name }

    private fun legacyTrackId(row: TripMetadataStore.TripRow): String =
        TripFormats.tripLabel(row.startMs, row.tzOffsetMin).replace('.', ':')
}

/** The finalizer view of a trips row. */
internal fun TripMetadataStore.TripRow.toTarget() = FinalizeTarget(
    tripId = id,
    startMs = startMs,
    title = title,
    pids = pids?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
    tzOffsetMin = tzOffsetMin
)
