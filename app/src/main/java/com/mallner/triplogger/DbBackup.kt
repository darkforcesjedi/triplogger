package com.mallner.triplogger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Database snapshots (TODO "Trip metadata database", Stage C): consistent
 * copies of triplogger.db in `backups/` INSIDE the journal root, so they
 * travel with the journal and can rebuild it when the live file is lost
 * or corrupt. The newest [KEEP] snapshots are kept.
 *
 * Preferred mechanism is SQLite's own `VACUUM INTO` (API 30+), which is
 * transactional and WAL-safe. On older API levels the WAL is checkpointed
 * and the file copied; every snapshot (both paths) is validated with
 * `PRAGMA integrity_check` before it counts, so a bad copy is deleted and
 * recorded instead of silently kept.
 *
 * The files are the truth; the `backups` table is bookkeeping only (an
 * unreadable database must not hide restorable files), which is also how
 * the restore flow finds candidates.
 */
object DbBackup {

    const val KEEP = 8
    private const val TAG = "DbBackup"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Outcome(val name: String?, val error: String?) {
        val ok get() = name != null
    }

    fun backupDir(root: File): File = File(root, "backups")

    /** Sortable snapshot name: triplogger-YYYYMMDD_HHMMSS.db. */
    internal fun snapshotName(nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        return "triplogger-$stamp.db"
    }

    private val nameRegex = Regex("""triplogger-\d{8}_\d{6}\.db""")

    /** Pure: names (out of [names]) to delete when only [keep] survive. */
    internal fun prunePlan(names: List<String>, keep: Int = KEEP): List<String> {
        val sorted = names.filter { nameRegex.matches(it) }.sortedDescending()
        return if (sorted.size <= keep) emptyList() else sorted.drop(keep)
    }

    /** Newest first; readable without opening any database. */
    fun availableBackups(root: File): List<File> =
        backupDir(root).listFiles { f -> f.isFile && nameRegex.matches(f.name) }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /**
     * The newest usable snapshot NAME to offer a restore for, or null.
     * Pure gating kept separate from the SQLite reads so it is unit
     * tested: never offer a rollback that cannot fix the problem. A
     * hand-copied journal folder missing from a healthy database is a
     * MERGE problem, not a backup problem; offering restore there would
     * blame the database forever and, once used, would roll the user
     * back without ever absorbing the folder. When folders are on disk
     * ([onDisk] non-empty), the backup must actually contain one of them
     * ([coversBackup]). An empty [onDisk] (nothing readable, e.g. a fresh
     * corrupt install) trusts the backup unconditionally.
     */
    internal fun restoreOfferName(newestName: String?, onDisk: Set<String>, coversBackup: Boolean): String? {
        if (newestName == null) return null
        if (onDisk.isNotEmpty() && !coversBackup) return null
        return newestName
    }

    /**
     * Whether the snapshot's trips table contains any of [folders]. A
     * restore is only worth offering for folders the backup actually
     * knows: hand-copied journals missing from a healthy database are a
     * merge problem, and a rollback that does not contain the trip would
     * warn about a "database issue" forever without fixing anything.
     */
    fun backupCoversAny(snapshot: File, folders: Set<String>): Boolean = runCatching {
        if (!snapshot.isFile) return false
        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT folder FROM trips", null).use { c ->
                while (c.moveToNext()) if (c.getString(0) in folders) return true
            }
        }
        false
    }.getOrDefault(false)

    /**
     * Snapshot when the database changed since the last one and that is a
     * day old (first run counts: no timestamp means never backed up).
     */
    suspend fun maybeDaily(store: TripMetadataStore) {
        if (!store.isDirty) return
        val last = store.metaGet(TripDatabase.META_LAST_BACKUP_AT)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < DAY_MS) return
        snapshot(store, "daily")
    }

    /** Take one snapshot, validate it, update bookkeeping, prune old ones. */
    suspend fun snapshot(store: TripMetadataStore, reason: String): Outcome =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val name = snapshotName(now)
            val dir = backupDir(store.journalRoot)
            if (!dir.isDirectory && !dir.mkdirs()) {
                return@withContext Outcome(null, "cannot create backups folder")
            }
            val dest = File(dir, name)
            var error: String? = runCatching { createSnapshot(store, dest) }
                .exceptionOrNull()?.let { it.message ?: "snapshot failed" }
            if (error == null) error = validate(dest)
            if (error != null) {
                Log.w(TAG, "Snapshot $name failed: $error")
                runCatching { dest.delete() }
                runCatching { store.recordBackup(name, now, dest.length().takeIf { dest.isFile }, false, null, error) }
                ActivityLog.log("db_backup_failed", "$name: $error")
                return@withContext Outcome(null, error)
            }
            val trips = runCatching { store.tripCount() }.getOrNull()
            store.recordBackup(name, now, dest.length(), true, trips, null)
            store.metaSet(TripDatabase.META_LAST_BACKUP_AT, now.toString())
            store.metaSet(TripDatabase.META_LAST_BACKUP_NAME, name)
            prune(dir, store)
            ActivityLog.log("db_backup", name, "reason" to reason)
            // Clear AFTER the bookkeeping writes: they would re-dirty,
            // and tomorrow's daily run must mean "changed on its own".
            store.clearDirty()
            Outcome(name, null)
        }

    private fun createSnapshot(store: TripMetadataStore, dest: File) {
        // VACUUM INTO refuses an existing target; ours are timestamp-unique.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            store.vacuumInto(dest.absolutePath)
        } else {
            store.checkpointWal()
            runCatching { store.dbFile.copyTo(dest, overwrite = true) }.getOrThrow()
        }
    }

    /** Null when [file] opens clean, else the reason it is untrustworthy. */
    private fun validate(file: File): String? = runCatching {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (!c.moveToFirst()) return@use "integrity check returned nothing"
                val verdict = c.getString(0)
                if (!verdict.equals("ok", ignoreCase = true)) return@use verdict
            }
            // The schema must be there: a snapshot of an empty/wrong file
            // passes integrity_check just fine.
            db.rawQuery("SELECT COUNT(*) FROM trips", null).use { it.moveToFirst() }
            null
        }
    }.getOrElse { it.message ?: "cannot open snapshot" }

    private fun prune(dir: File, store: TripMetadataStore) {
        val names = dir.list()?.toList() ?: return
        for (name in prunePlan(names)) {
            runCatching { File(dir, name).delete() }
                .onFailure { Log.w(TAG, "Could not prune $name", it) }
        }
        val keep = names.filter { nameRegex.matches(it) }.toSet() - prunePlan(names).toSet()
        runCatching { store.pruneBackupRows(keep) }
    }

    /**
     * Replace the live database with snapshot [name] after validating it.
     * Closes and clears every cached handle first; a failed restore leaves
     * the backup untouched and the (broken) database alone or re-created.
     */
    suspend fun restore(context: Context, journalUri: String, name: String): Outcome =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = JournalPath.rootDir(journalUri)
                    ?: return@withContext Outcome(null, "journal root not resolvable")
                val src = File(backupDir(root), name)
                if (!src.isFile) return@withContext Outcome(null, "backup file missing")
                validate(src)?.let { return@withContext Outcome(null, "backup failed integrity check: $it") }

                // Drop cached handles before replacing the file underneath.
                Storage.dropMetadataStore(journalUri)
                SettingsDb.clearAttachment()
                ActivityLog.clearAttachment()

                val dbFile = File(root, TripDatabase.DB_FILE_NAME)
                deleteDatabaseFiles(root)
                FileInputStream(src).use { input ->
                    FileOutputStream(dbFile).use { output -> input.copyTo(output) }
                }

                val settings = AppSettingsRepository(context).appSettingsFlow.first()
                val store = Storage.metadataStore(context, journalUri)
                    ?: return@withContext Outcome(null, "restored file cannot be opened")
                store.metaSet(TripDatabase.META_LAST_RESTORE_AT, System.currentTimeMillis().toString())
                BootFlags.resync(context)
                JournalSync.ensureSynced(context, settings, force = true)
                ActivityLog.log("backup_restored", name)
                Outcome(name, null)
            }.getOrElse {
                Log.e(TAG, "Restore of $name failed", it)
                Outcome(null, it.message ?: "restore failed")
            }
        }

    private fun deleteDatabaseFiles(root: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val f = File(root, TripDatabase.DB_FILE_NAME + suffix)
            if (f.isFile && !f.delete()) Log.w(TAG, "Could not delete ${f.name}")
        }
    }
}
