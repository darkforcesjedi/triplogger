package com.mallner.triplogger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * The trip metadata database (TODO "Trip metadata database"). One SQLite
 * file "triplogger.db" lives in the journal root beside pending/ archive/
 * failed/ and is the single source of truth for trip state, outputs,
 * odometer readings, activity history, app settings and backup bookkeeping.
 * Raw journals (gps/obd CSVs, odo.csv) stay flat files; the DB references
 * them through trips.folder + raw_state (CSV is canonical: a corrupt DB
 * must never take the recorded samples with it).
 *
 * Journal files never move on their own - only rows do - so all ids are the
 * epoch track id "t<start_ms>" (rename-stable, DST-safe). The current folder
 * name is a column, never the key.
 */
class TripDatabase(context: Context, file: File) :
    SQLiteOpenHelper(context, file.absolutePath, null, SCHEMA_VERSION) {

    /** Absolute path of the database file (used by backup VACUUM INTO). */
    val dbFilePath: String = file.absolutePath

    companion object {
        const val SCHEMA_VERSION = 2
        const val DB_FILE_NAME = "triplogger.db"
        const val BACKUPS_DIR_NAME = "backups"

        /** meta keys maintained outside transactions of individual features. */
        const val META_SCHEMA_MIGRATION = "schema_migration"
        const val META_ROOT_TOKEN = "root_token"
        const val META_DB_DIRTY = "db_dirty"
        const val META_LAST_BACKUP_AT = "last_backup_at"
        const val META_LAST_BACKUP_NAME = "last_backup_name"
        const val META_LAST_RESTORE_AT = "last_restore_at"
        const val META_LAST_SCAN_MS = "last_scan_ms"
        const val META_LAST_RECONCILE_MS = "last_reconcile_ms"
        const val META_JOURNAL_MODE = "journal_mode"

        // Shared state names (rows carry the same literals; keep in sync with JournalStore folder layout)
        const val STATE_PENDING = "pending"
        const val STATE_ARCHIVE = "archive"
        const val STATE_FAILED = "failed"

        const val RAW_PRESENT = "present"
        const val RAW_PURGED_RETENTION = "purged_retention"
        const val RAW_PURGED_MANUAL = "purged_manual"
        const val RAW_MISSING = "missing"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // WAL for concurrent UI reads while the service writes; some mounted
        // storage (FUSE/SD adapters) rejects WAL - fall back and remember.
        if (!walMode(db)) {
            runCatching { db.rawQuery("PRAGMA journal_mode=TRUNCATE", null).use { it.moveToFirst() } }
        }
        // Assignment pragmas answer with a result row, so they must not go
        // through execSQL on Android ("Queries can be performed ... only").
        runCatching { db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() } }
    }

    private fun walMode(db: SQLiteDatabase): Boolean = try {
        // The pragma answers with the resulting mode instead of throwing
        // when the mount refuses WAL, so inspect the value.
        db.rawQuery("PRAGMA journal_mode=WAL", null).use {
            it.moveToFirst() && it.getString(0).equals("wal", ignoreCase = true)
        }
    } catch (_: Exception) {
        false
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trips(
                id TEXT PRIMARY KEY,
                folder TEXT NOT NULL UNIQUE,
                state TEXT NOT NULL CHECK(state IN ('pending','archive','failed')),
                start_ms INTEGER NOT NULL,
                end_ms INTEGER,
                entries INTEGER NOT NULL DEFAULT 0,
                segments INTEGER NOT NULL DEFAULT 0,
                pids TEXT,
                title TEXT,
                trigger TEXT,
                category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
                tz_id TEXT,
                tz_offset_min INTEGER,
                tz_name TEXT,
                place_start TEXT,
                place_end TEXT,
                distance_source TEXT CHECK(distance_source IN ('odo','obd','gps_speed','track')),
                samples_backend TEXT NOT NULL DEFAULT 'csv',
                raw_state TEXT NOT NULL DEFAULT 'present'
                    CHECK(raw_state IN ('present','purged_retention','purged_manual','missing')),
                raw_purged_at INTEGER,
                raw_bytes INTEGER,
                raw_file_count INTEGER,
                status_reason TEXT,
                converted_at INTEGER,
                app_version TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_trips_start ON trips(start_ms DESC)")
        db.execSQL("CREATE INDEX idx_trips_state ON trips(state, start_ms DESC)")
        db.execSQL("CREATE INDEX idx_trips_cat ON trips(category_id)")

        db.execSQL(
            """
            CREATE TABLE metrics(
                trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT '',
                value REAL NOT NULL,
                note TEXT,
                PRIMARY KEY(trip_id, kind, source)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_metrics_kind ON metrics(kind, source)")

        db.execSQL(
            """
            CREATE TABLE trip_ext(
                trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                key TEXT NOT NULL,
                value TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(trip_id, key)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE categories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                color INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE outputs(
                trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                bytes INTEGER,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(trip_id, kind, name)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE odo_readings(
                trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
                ts INTEGER NOT NULL,
                km REAL NOT NULL,
                PRIMARY KEY(trip_id, ts)
            )
            """.trimIndent()
        )

        createStops(db)

        db.execSQL(
            """
            CREATE TABLE activity(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                event TEXT NOT NULL,
                trip_id TEXT REFERENCES trips(id) ON DELETE SET NULL,
                message TEXT,
                fields TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_activity_ts ON activity(ts DESC)")
        db.execSQL("CREATE INDEX idx_activity_event ON activity(event, ts DESC)")
        db.execSQL("CREATE INDEX idx_activity_trip ON activity(trip_id, ts DESC)")

        db.execSQL(
            """
            CREATE TABLE settings(
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE backups(
                name TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                bytes INTEGER,
                integrity_ok INTEGER NOT NULL DEFAULT 0,
                trips_at_backup INTEGER,
                error TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE meta(
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // PRAGMA user_version is written by SQLiteOpenHelper after onCreate.
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: stop detection (StopDetector) records confirmed dwells.
        if (oldVersion < 2) createStops(db)
    }

    /** In-trip stops recorded by stop detection (annotate mode). */
    internal fun createStops(db: SQLiteDatabase) = db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS stops(
            trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
            start_ms INTEGER NOT NULL,
            end_ms INTEGER NOT NULL,
            duration_s INTEGER NOT NULL,
            latitude REAL,
            longitude REAL,
            source TEXT NOT NULL,
            PRIMARY KEY(trip_id, start_ms)
        )
        """.trimIndent()
    )
}
