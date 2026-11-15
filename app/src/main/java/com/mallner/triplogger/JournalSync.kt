package com.mallner.triplogger

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-stop trip.properties -> database migration and folder/row
 * reconciliation (TODO "Trip metadata database"). Called from the service
 * before crash recovery and from the Trips tab before its first load;
 * a full scan runs once per process (plus on demand after storage changes).
 *
 * Dev-scale dataset on purpose: the scan lists folders, imports every
 * trip.properties it finds (known keys to columns, distances to metrics,
 * unknown keys to trip_ext) and deletes the file. Folders without rows are
 * re-imported on every run, so an interrupted migration heals itself.
 */
object JournalSync {

    private val mutex = Mutex()

    @Volatile
    private var scannedUri: String? = null

    /**
     * Open the metadata store for the configured journal root, running the
     * migration/reconciliation scan on first use. Null when the journal
     * root is not resolvable to a database (no folder chosen, secondary
     * storage, corrupt file): callers then cannot log or list trips.
     */
    suspend fun ensureSynced(context: Context, settings: AppSettings, force: Boolean = false): TripMetadataStore? {
        val db = Storage.metadataStore(context, settings) ?: return null
        if (scannedUri == settings.tempLocationUri && !force) return db
        mutex.withLock {
            if (scannedUri == settings.tempLocationUri && !force) return db
            // Failures stay un-stamped so the next caller retries the scan.
            runCatching { scan(context, settings, db) }
                .onSuccess { scannedUri = settings.tempLocationUri }
                .onFailure { ActivityLog.log("db_scan_failed", it.message ?: "unknown") }
            return db
        }
    }

    private fun scan(context: Context, settings: AppSettings, db: TripMetadataStore) {
        val store = Storage.journalStore(context, settings) ?: return
        val now = System.currentTimeMillis()
        val folders = store.allTripFolders()
        val plan = Reconciliation.reconcile(
            db.allRowsLite(),
            folders.map { Reconciliation.DiskFolder(it.name, it.state, it.hasContent) }
        )

        var imported = 0
        for (stray in plan.importFolders) {
            val origin = folders.firstOrNull { it.name == stray.name } ?: continue
            if (ensureRow(db, origin.trip, origin.state, now) != null) imported++
        }
        db.applyReconciliation(plan) { folder -> db.tripByFolder(folder)?.id }

        // Empty leftover directories with no row are interrupted SAF
        // operations, not trips; remove them so they never resurface.
        for (name in plan.emptyStrays) {
            folders.firstOrNull { it.name == name }?.let { runCatching { store.delete(it.trip) } }
        }

        db.metaSet(TripDatabase.META_LAST_SCAN_MS, now.toString())
        if (imported > 0) ActivityLog.log("db_imported", "$imported trips from trip.properties")
        if (plan.markMissing.isNotEmpty() || plan.revive.isNotEmpty() || plan.stateDrift.isNotEmpty()) {
            ActivityLog.log(
                "db_reconciled",
                "missing=${plan.markMissing.size} revived=${plan.revive.size} moved=${plan.stateDrift.size}"
            )
        }
    }

    /**
     * Row for [trip], importing trip.properties when present or deriving a
     * minimal pending row from the folder name. Null only for hopeless
     * folders (no properties, unparseable name); those stay untouched and
     * are reported.
     */
    fun ensureRow(
        db: TripMetadataStore,
        trip: OrphanTrip,
        state: String,
        nowMs: Long = System.currentTimeMillis()
    ): TripMetadataStore.TripRow? {
        db.tripByFolder(trip.name)?.let { return it }
        val props = trip.readProps()
        val effective = props.ifEmpty {
            parseFolderStartMs(trip.name)?.let { mapOf("start_ms" to it.toString()) } ?: props
        }
        return when (val result = PropertiesImport.import(trip.name, state, effective, nowMs)) {
            is PropertiesImport.Result.Success -> {
                db.insertImported(result.trip)
                if (props.isNotEmpty()) runCatching { trip.deleteProps() }
                db.tripByFolder(trip.name)
            }
            is PropertiesImport.Result.Invalid -> {
                ActivityLog.log("db_import_skipped", trip.name, "reason" to result.reason)
                null
            }
        }
    }

    /** Folder names are wall-clock ids "yyyyMMdd_HHmmss" (all versions). */
    internal fun parseFolderStartMs(name: String): Long? = runCatching {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name)?.time
    }.getOrNull()
}
