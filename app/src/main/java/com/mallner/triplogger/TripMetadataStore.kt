package com.mallner.triplogger

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/**
 * CRUD facade over [TripDatabase] (TODO "Trip metadata database"). All
 * callers (service, repository, settings, activity, backups) go through
 * this class; raw trip.properties access disappears with Stage B.
 *
 * Rows are keyed by the epoch track id "t<start_ms>" and the journal
 * folder name is data, never the key. Every mutation marks meta.db_dirty
 * so the backup scheduler only snapshots when there is news.
 *
 * Must be used from a background dispatcher (SQLite I/O in the journal
 * root can be slow on shared storage).
 */
class TripMetadataStore(context: Context, private val rootDir: File) : TripMetadataWriter {

    private val helper = TripDatabase(context, File(rootDir, TripDatabase.DB_FILE_NAME))

    /** Absolute path of triplogger.db (backup VACUUM INTO target source). */
    val dbFile: File get() = File(helper.dbFilePath)

    init {
        // Force the open (creates/migrates) and stamp bookkeeping.
        val db = helper.writableDatabase
        metaSetDb(db, TripDatabase.META_SCHEMA_MIGRATION, TripDatabase.SCHEMA_VERSION.toString())
        if (metaGetDb(db, TripDatabase.META_ROOT_TOKEN) == null) {
            metaSetDb(db, TripDatabase.META_ROOT_TOKEN, UUID.randomUUID().toString())
        }
        // Record whether WAL stuck on this mount (FUSE variants differ).
        val mode = runCatching {
            db.rawQuery("PRAGMA journal_mode", null).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        metaSetDb(db, TripDatabase.META_JOURNAL_MODE, mode ?: "unknown")
    }

    /** Journal root folder; `backups/` snapshots live beside the database. */
    val journalRoot: File get() = rootDir

    /** Identifies this journal root+DB pair; survives folder renames. */
    val rootToken: String
        get() = metaGet(TripDatabase.META_ROOT_TOKEN)
            ?: UUID.randomUUID().toString().also { metaSet(TripDatabase.META_ROOT_TOKEN, it) }

    fun close() = helper.close()

    // ------------------------------------------------------------------
    // meta + dirty flag
    // ------------------------------------------------------------------

    fun metaGet(key: String): String? = metaGetDb(helper.readableDatabase, key)
    fun metaSet(key: String, value: String) = write { metaSetDb(it, key, value) }

    private fun metaGetDb(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun metaSetDb(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO meta(key, value, updated_at) VALUES(?,?,?)",
            arrayOf(key, value, System.currentTimeMillis())
        )
    }

    val isDirty: Boolean
        get() = metaGet(TripDatabase.META_DB_DIRTY) == "1"

    /**
     * Called after every committed mutation; a direct single-row write so
     * it never nests inside a [write] transaction.
     */
    fun markDirty() = metaSetDb(helper.writableDatabase, TripDatabase.META_DB_DIRTY, "1")
    fun clearDirty() = metaSetDb(helper.writableDatabase, TripDatabase.META_DB_DIRTY, "0")

    // ------------------------------------------------------------------
    // settings (Stage C: AppSettingsRepository facade; JSON scalar values)
    // ------------------------------------------------------------------

    fun settingGet(key: String): String? =
        helper.readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun settingPut(key: String, valueJson: String) = write { db ->
        db.execSQL(
            "INSERT OR REPLACE INTO settings(key, value, updated_at) VALUES(?,?,?)",
            arrayOf(key, valueJson, System.currentTimeMillis())
        )
    }

    fun settingDelete(key: String) = write { db ->
        db.execSQL("DELETE FROM settings WHERE key=?", arrayOf(key))
    }

    fun settingsAll(): Map<String, String> =
        helper.readableDatabase.rawQuery("SELECT key, value FROM settings", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    // ------------------------------------------------------------------
    // trips
    // ------------------------------------------------------------------

    data class TripRow(
        val id: String,
        val folder: String,
        val state: String,
        val startMs: Long,
        val endMs: Long?,
        val entries: Int,
        val segments: Int,
        val pids: String?,
        val title: String?,
        val trigger: String?,
        val categoryId: Long?,
        val tzId: String?,
        val tzOffsetMin: Int?,
        val tzName: String?,
        val placeStart: String?,
        val placeEnd: String?,
        val distanceSource: String?,
        val samplesBackend: String,
        val rawState: String,
        val rawPurgedAt: Long?,
        val rawBytes: Long?,
        val rawFileCount: Int?,
        val statusReason: String?,
        val convertedAt: Long?,
        val appVersion: String?,
        val createdAt: Long,
        val updatedAt: Long
    )

    /** Live journal opened by the service (pending, nothing derived yet). */
    fun insertLiveTrip(
        id: String,
        folder: String,
        startMs: Long,
        pids: String?,
        trigger: String?,
        tzId: String?,
        tzOffsetMin: Int?
    ) = write { db ->
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT OR IGNORE INTO trips(id, folder, state, start_ms, pids, trigger, tz_id, " +
                "tz_offset_min, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, folder, TripDatabase.STATE_PENDING, startMs, pids, trigger, tzId,
                tzOffsetMin, now, now)
        )
    }

    /** One migration row from [PropertiesImport] (trips + metrics + outputs + ext). */
    fun insertImported(imported: PropertiesImport.Imported) = write { db ->
        insertColumnRow(db, imported.columns)
        imported.metrics.forEach { m ->
            db.execSQL(
                "INSERT OR REPLACE INTO metrics(trip_id, kind, source, value, note) VALUES(?,?,?,?,?)",
                arrayOf(imported.id, m.kind, m.source, m.value, m.note)
            )
        }
        imported.outputs.forEach { o ->
            db.execSQL(
                "INSERT OR REPLACE INTO outputs(trip_id, kind, name, created_at) VALUES(?,?,?,?)",
                arrayOf(imported.id, o.kind, o.name, System.currentTimeMillis())
            )
        }
        imported.ext.forEach { (k, v) ->
            db.execSQL(
                "INSERT OR REPLACE INTO trip_ext(trip_id, key, value, updated_at) VALUES(?,?,?,?)",
                arrayOf(imported.id, k, v, System.currentTimeMillis())
            )
        }
    }

    private fun insertColumnRow(db: SQLiteDatabase, columns: Map<String, Any?>) {
        val cols = columns.keys.joinToString(", ")
        val marks = columns.keys.joinToString(", ") { "?" }
        val stmt = db.compileStatement("INSERT OR REPLACE INTO trips($cols) VALUES($marks)")
        columns.values.forEachIndexed { i, v -> bind(stmt, i + 1, v) }
        stmt.executeInsert()
        stmt.close()
    }

    private fun bind(stmt: android.database.sqlite.SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.bindNull(index)
            is Long -> stmt.bindLong(index, value)
            is Int -> stmt.bindLong(index, value.toLong())
            is Double -> stmt.bindDouble(index, value)
            else -> stmt.bindString(index, value.toString())
        }
    }

    fun tripByFolder(folder: String): TripRow? =
        queryTrips("folder=?", arrayOf(folder)).firstOrNull()

    fun tripById(id: String): TripRow? =
        queryTrips("id=?", arrayOf(id)).firstOrNull()

    /** Live rows (optionally one state), newest first; excludes soft-deleted. */
    fun trips(state: String? = null): List<TripRow> =
        if (state == null) queryTrips("deleted_at IS NULL", null)
        else queryTrips("state=? AND deleted_at IS NULL", arrayOf(state))

    fun allRowsLite(): List<Reconciliation.TripRowLite> =
        helper.readableDatabase.rawQuery(
            "SELECT folder, state, raw_state FROM trips WHERE deleted_at IS NULL", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Reconciliation.TripRowLite(c.getString(0), c.getString(1), c.getString(2)))
                }
            }
        }

    private fun queryTrips(where: String, args: Array<String>?): List<TripRow> =
        helper.readableDatabase.rawQuery(
            "SELECT * FROM trips WHERE $where ORDER BY start_ms DESC", args
        ).use { c ->
            buildList { while (c.moveToNext()) add(readTripRow(c)) }
        }

    private fun readTripRow(c: Cursor) = TripRow(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        folder = c.getString(c.getColumnIndexOrThrow("folder")),
        state = c.getString(c.getColumnIndexOrThrow("state")),
        startMs = c.getLong(c.getColumnIndexOrThrow("start_ms")),
        endMs = c.columnLongOrNull("end_ms"),
        entries = c.getInt(c.getColumnIndexOrThrow("entries")),
        segments = c.getInt(c.getColumnIndexOrThrow("segments")),
        pids = c.columnStringOrNull("pids"),
        title = c.columnStringOrNull("title"),
        trigger = c.columnStringOrNull("trigger"),
        categoryId = c.columnLongOrNull("category_id"),
        tzId = c.columnStringOrNull("tz_id"),
        tzOffsetMin = c.columnIntOrNull("tz_offset_min"),
        tzName = c.columnStringOrNull("tz_name"),
        placeStart = c.columnStringOrNull("place_start"),
        placeEnd = c.columnStringOrNull("place_end"),
        distanceSource = c.columnStringOrNull("distance_source"),
        samplesBackend = c.getString(c.getColumnIndexOrThrow("samples_backend")),
        rawState = c.getString(c.getColumnIndexOrThrow("raw_state")),
        rawPurgedAt = c.columnLongOrNull("raw_purged_at"),
        rawBytes = c.columnLongOrNull("raw_bytes"),
        rawFileCount = c.columnIntOrNull("raw_file_count"),
        statusReason = c.columnStringOrNull("status_reason"),
        convertedAt = c.columnLongOrNull("converted_at"),
        appVersion = c.columnStringOrNull("app_version"),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    /** Generic single-column update plus updated_at stamp. */
    private fun setTripColumn(tripId: String, column: String, value: Any?) = write { db ->
        db.execSQL(
            "UPDATE trips SET $column=?, updated_at=? WHERE id=?",
            arrayOf(value, System.currentTimeMillis(), tripId)
        )
    }

    fun setTitle(tripId: String, title: String?) = setTripColumn(tripId, "title", title)
    fun setTzName(tripId: String, tzName: String?) = setTripColumn(tripId, "tz_name", tzName)
    fun setStatusReason(tripId: String, reason: String?) = setTripColumn(tripId, "status_reason", reason)
    fun setState(tripId: String, state: String) = setTripColumn(tripId, "state", state)
    fun setCategory(tripId: String, categoryId: Long?) = setTripColumn(tripId, "category_id", categoryId)
    fun setDistanceSource(tripId: String, source: String?) = setTripColumn(tripId, "distance_source", source)

    fun setPlaces(tripId: String, start: String?, end: String?) = write { db ->
        db.execSQL(
            "UPDATE trips SET place_start=?, place_end=?, updated_at=? WHERE id=?",
            arrayOf(start, end, System.currentTimeMillis(), tripId)
        )
    }

    /** Folder rename and/or state move after a relocate. */
    fun relocate(tripId: String, newFolder: String?, newState: String?) = write { db ->
        db.execSQL(
            "UPDATE trips SET folder=COALESCE(?, folder), state=COALESCE(?, state), " +
                "updated_at=? WHERE id=?",
            arrayOf(newFolder, newState, System.currentTimeMillis(), tripId)
        )
    }

    /** Raw-data lifecycle transition (delete folder files first, then here). */
    fun setRawState(tripId: String, rawState: String, purgedAt: Long? = null) = write { db ->
        db.execSQL(
            "UPDATE trips SET raw_state=?, raw_purged_at=?, updated_at=? WHERE id=?",
            arrayOf(rawState, purgedAt, System.currentTimeMillis(), tripId)
        )
    }

    /** Finalization writes: derived summary fields in one transaction. */
    override fun recordConversion(
        tripId: String,
        endMs: Long,
        entries: Int,
        segments: Int,
        distanceSource: String?,
        convertedAt: Long,
        appVersion: String?,
        rawBytes: Long?,
        rawFileCount: Int?
    ) = write { db ->
        db.execSQL(
            "UPDATE trips SET end_ms=?, entries=?, segments=?, distance_source=?, " +
                "converted_at=?, app_version=?, raw_bytes=?, raw_file_count=?, " +
                "status_reason=NULL, updated_at=? WHERE id=?",
            arrayOf(
                endMs, entries, segments, distanceSource, convertedAt, appVersion,
                rawBytes, rawFileCount, System.currentTimeMillis(), tripId
            )
        )
    }

    fun deleteTrip(tripId: String) = write { db ->
        db.execSQL("DELETE FROM trips WHERE id=?", arrayOf(tripId))
    }

    // ------------------------------------------------------------------
    // metrics / trip_ext / outputs / odometer
    // ------------------------------------------------------------------

    data class MetricRow(val kind: String, val source: String, val value: Double, val note: String?)
    data class OutputRow(val kind: String, val name: String, val bytes: Long?, val createdAt: Long)

    /** Distance estimates keyed by source, e.g. "track" -> 1234.5. */
    fun distances(tripId: String): Map<String, Double> = metrics(tripId)
        .filter { it.kind == "distance_m" }
        .associate { it.source to it.value }

    fun metrics(tripId: String): List<MetricRow> =
        helper.readableDatabase.rawQuery(
            "SELECT kind, source, value, note FROM metrics WHERE trip_id=? " +
                "ORDER BY kind, source", arrayOf(tripId)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(MetricRow(c.getString(0), c.getString(1), c.getDouble(2), c.getString(3)))
                }
            }
        }

    override fun putMetric(tripId: String, kind: String, source: String, value: Double, note: String?) =
        write { db ->
            db.execSQL(
                "INSERT OR REPLACE INTO metrics(trip_id, kind, source, value, note) VALUES(?,?,?,?,?)",
                arrayOf(tripId, kind, source, value, note)
            )
        }

    /** All metrics from one recordConversion-style batch. */
    fun putMetrics(tripId: String, metrics: List<PropertiesImport.Metric>) = write { db ->
        metrics.forEach { m ->
            db.execSQL(
                "INSERT OR REPLACE INTO metrics(trip_id, kind, source, value, note) VALUES(?,?,?,?,?)",
                arrayOf(tripId, m.kind, m.source, m.value, m.note)
            )
        }
    }

    // ------------------------------------------------------------------ stops

    /** Persist confirmed stops detected during a recording (annotate mode). */
    fun insertStops(tripId: String, stops: List<StopEvent>) = write { db ->
        stops.forEach { s ->
            db.execSQL(
                "INSERT OR REPLACE INTO stops(trip_id, start_ms, end_ms, duration_s, latitude, longitude, source) " +
                    "VALUES(?,?,?,?,?,?,?)",
                arrayOf(tripId, s.startMs, s.endMs, s.durationSec, s.latitude, s.longitude, s.source)
            )
        }
    }

    /** Stops of one trip, earliest first. */
    fun listStops(tripId: String): List<StopRow> =
        helper.readableDatabase.rawQuery(
            "SELECT start_ms, end_ms, duration_s, latitude, longitude, source FROM stops " +
                "WHERE trip_id=? ORDER BY start_ms",
            arrayOf(tripId)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        StopRow(
                            c.getLong(0), c.getLong(1), c.getLong(2),
                            if (c.isNull(3)) null else c.getDouble(3),
                            if (c.isNull(4)) null else c.getDouble(4),
                            c.getString(5)
                        )
                    )
                }
            }
        }

    data class StopRow(
        val startMs: Long,
        val endMs: Long,
        val durationSec: Long,
        val latitude: Double?,
        val longitude: Double?,
        val source: String
    )

    fun extValue(tripId: String, key: String): String? =
        helper.readableDatabase.rawQuery(
            "SELECT value FROM trip_ext WHERE trip_id=? AND key=?", arrayOf(tripId, key)
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun setExt(tripId: String, key: String, value: String?) = write { db ->
        if (value == null) {
            db.execSQL("DELETE FROM trip_ext WHERE trip_id=? AND key=?", arrayOf(tripId, key))
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO trip_ext(trip_id, key, value, updated_at) VALUES(?,?,?,?)",
                arrayOf(tripId, key, value, System.currentTimeMillis())
            )
        }
    }

    override fun rememberOutput(tripId: String, kind: String, name: String, bytes: Long?) = write { db ->
        db.execSQL(
            "INSERT OR REPLACE INTO outputs(trip_id, kind, name, bytes, created_at) VALUES(?,?,?,?,?)",
            arrayOf(tripId, kind, name, bytes, System.currentTimeMillis())
        )
    }

    fun outputs(tripId: String): List<OutputRow> =
        helper.readableDatabase.rawQuery(
            "SELECT kind, name, bytes, created_at FROM outputs WHERE trip_id=? ORDER BY kind",
            arrayOf(tripId)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(OutputRow(c.getString(0), c.getString(1), c.longOrNull(2), c.getLong(3)))
            }
        }

    fun addOdoReading(tripId: String, ts: Long, km: Double) = write { db ->
        db.execSQL(
            "INSERT OR IGNORE INTO odo_readings(trip_id, ts, km) VALUES(?,?,?)",
            arrayOf(tripId, ts, km)
        )
    }

    /** Mirror of odo.csv after conversion (idempotent re-runs included). */
    override fun replaceOdoReadings(tripId: String, readings: List<Pair<Long, Double>>) = write { db ->
        db.execSQL("DELETE FROM odo_readings WHERE trip_id=?", arrayOf(tripId))
        readings.forEach { (ts, km) ->
            db.execSQL(
                "INSERT OR IGNORE INTO odo_readings(trip_id, ts, km) VALUES(?,?,?)",
                arrayOf(tripId, ts, km)
            )
        }
    }

    /** One kind across every trip: trip id -> source -> value (Trips tab list). */
    fun metricsIndex(kind: String): Map<String, Map<String, Double>> =
        helper.readableDatabase.rawQuery(
            "SELECT trip_id, source, value FROM metrics WHERE kind=?", arrayOf(kind)
        ).use { c ->
            buildMap<String, MutableMap<String, Double>> {
                while (c.moveToNext()) {
                    getOrPut(c.getString(0)) { mutableMapOf() }[c.getString(1)] = c.getDouble(2)
                }
            }
        }

    /** One output kind across every trip: trip id -> file name. */
    fun outputsIndex(kind: String): Map<String, String> =
        helper.readableDatabase.rawQuery(
            "SELECT trip_id, name FROM outputs WHERE kind=?", arrayOf(kind)
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    fun odoReadings(tripId: String): List<Pair<Long, Double>> =
        helper.readableDatabase.rawQuery(
            "SELECT ts, km FROM odo_readings WHERE trip_id=? ORDER BY ts", arrayOf(tripId)
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getDouble(1)) }
        }

    // ------------------------------------------------------------------
    // activity log (Stage C swaps ActivityLog over)
    // ------------------------------------------------------------------

    data class ActivityRow(
        val id: Long,
        val ts: Long,
        val event: String,
        val tripId: String?,
        val message: String?,
        val fields: String?
    )

    fun logActivity(
        ts: Long,
        event: String,
        tripId: String? = null,
        message: String? = null,
        fieldsJson: String? = null
    ): Long = writeResult { db ->
        db.execSQL(
            "INSERT INTO activity(ts, event, trip_id, message, fields) VALUES(?,?,?,?,?)",
            arrayOf(ts, event, tripId, message, fieldsJson)
        )
        db.compileStatement("SELECT last_insert_rowid()").simpleQueryForLong()
    }

    /** Newest first; [event] optional filter; for paging pass the lowest id seen. */
    fun recentActivity(limit: Int = 500, event: String? = null, beforeId: Long = 0): List<ActivityRow> {
        val sql = buildString {
            append("SELECT id, ts, event, trip_id, message, fields FROM activity")
            val clauses = mutableListOf<String>()
            if (event != null) clauses += "event=?"
            if (beforeId > 0) clauses += "id<?"
            if (clauses.isNotEmpty()) append(" WHERE ").append(clauses.joinToString(" AND "))
            append(" ORDER BY id DESC LIMIT ")
            append(limit)
        }
        val args = mutableListOf<String?>().apply {
            if (event != null) add(event)
            if (beforeId > 0) add(beforeId.toString())
        }
        return helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ActivityRow(
                            c.getLong(0), c.getLong(1), c.getString(2),
                            if (c.isNull(3)) null else c.getString(3),
                            if (c.isNull(4)) null else c.getString(4),
                            if (c.isNull(5)) null else c.getString(5)
                        )
                    )
                }
            }
        }
    }

    /** Drop the oldest rows beyond [maxRows] (keeps the table bounded). */
    fun trimActivity(maxRows: Int) = write { db ->
        db.execSQL(
            "DELETE FROM activity WHERE id < " +
                "(SELECT MIN(id) FROM (SELECT id FROM activity ORDER BY id DESC LIMIT ?))",
            arrayOf(maxRows)
        )
    }

    // ------------------------------------------------------------------
    // backup bookkeeping (truth is the files in backups/)
    // ------------------------------------------------------------------

    data class BackupRow(
        val name: String,
        val createdAt: Long,
        val bytes: Long?,
        val integrityOk: Boolean,
        val tripsAtBackup: Long?,
        val error: String?
    )

    fun recordBackup(
        name: String,
        createdAt: Long,
        bytes: Long?,
        integrityOk: Boolean,
        tripsAtBackup: Long?,
        error: String?
    ) = write { db ->
        db.execSQL(
            "INSERT OR REPLACE INTO backups(name, created_at, bytes, integrity_ok, " +
                "trips_at_backup, error) VALUES(?,?,?,?,?,?)",
            arrayOf(name, createdAt, bytes, if (integrityOk) 1 else 0, tripsAtBackup, error)
        )
    }

    fun backups(): List<BackupRow> =
        helper.readableDatabase.rawQuery(
            "SELECT name, created_at, bytes, integrity_ok, trips_at_backup, error " +
                "FROM backups ORDER BY name DESC", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        BackupRow(
                            c.getString(0), c.getLong(1), c.longOrNull(2),
                            c.getInt(3) != 0, c.longOrNull(4),
                            if (c.isNull(5)) null else c.getString(5)
                        )
                    )
                }
            }
        }

    fun tripCount(): Long =
        helper.readableDatabase.compileStatement(
            "SELECT COUNT(*) FROM trips WHERE deleted_at IS NULL"
        ).simpleQueryForLong()

    /**
     * SQLite-native consistent snapshot at [destPath] (API 30+). Must run
     * OUTSIDE any transaction (VACUUM semantics) and the target must not
     * exist, so it bypasses the [write] helpers entirely.
     */
    fun vacuumInto(destPath: String) {
        helper.writableDatabase.rawQuery("VACUUM INTO ?", arrayOf(destPath)).use { it.moveToFirst() }
    }

    /** Fold the WAL back into the main file before a pre-API-30 copy. */
    fun checkpointWal() {
        helper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    /** Bookkeeping rows for pruned snapshot files (files are the truth). */
    fun pruneBackupRows(keep: Set<String>) = write { db ->
        if (keep.isEmpty()) {
            db.execSQL("DELETE FROM backups WHERE name LIKE 'triplogger-%'")
        } else {
            val marks = keep.joinToString(",") { "?" }
            db.execSQL(
                "DELETE FROM backups WHERE name LIKE 'triplogger-%' AND name NOT IN ($marks)",
                keep.toTypedArray()
            )
        }
    }

    /**
     * Apply a computed [Reconciliation.Plan]: flag vanished folders missing,
     * revive restored ones, fix external state drift. Returns rows that
     * still need a properties import (folder-only trips); the caller runs
     * [insertImported] per folder it can read.
     */
    fun applyReconciliation(plan: Reconciliation.Plan, idForFolder: (String) -> String?) = write { db ->
        plan.markMissing.forEach { folder ->
            idForFolder(folder)?.let {
                db.execSQL(
                    "UPDATE trips SET raw_state=?, updated_at=? WHERE id=?",
                    arrayOf(TripDatabase.RAW_MISSING, System.currentTimeMillis(), it)
                )
            }
        }
        plan.revive.forEach { folder ->
            idForFolder(folder)?.let {
                db.execSQL(
                    "UPDATE trips SET raw_state=?, raw_purged_at=NULL, updated_at=? WHERE id=?",
                    arrayOf(TripDatabase.RAW_PRESENT, System.currentTimeMillis(), it)
                )
            }
        }
        plan.stateDrift.forEach { (folder, diskState) ->
            idForFolder(folder)?.let {
                db.execSQL(
                    "UPDATE trips SET state=?, updated_at=? WHERE id=?",
                    arrayOf(diskState, System.currentTimeMillis(), it)
                )
            }
        }
    }

    // ------------------------------------------------------------------

    private inline fun write(block: (SQLiteDatabase) -> Unit) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        markDirty()
    }

    private inline fun <T> writeResult(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        val result: T
        try {
            result = block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        markDirty()
        return result
    }

    private fun Cursor.columnStringOrNull(name: String): String? =
        getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.columnLongOrNull(name: String): Long? =
        getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.columnIntOrNull(name: String): Int? =
        getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }

    private fun Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)
}
