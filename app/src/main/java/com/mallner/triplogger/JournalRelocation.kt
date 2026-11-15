package com.mallner.triplogger

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moving, copying, or leaving behind the journal when the user re-picks
 * the temporary storage folder (TODO "Trip metadata database", Stage C4).
 *
 * Only journal-owned entries travel: the three state folders, `backups/`,
 * and the triplogger.db files (the metadata database moves WITH its
 * samples, keeping raw journals and metadata consistent). Foreign files
 * in either folder are never touched.
 *
 * A target whose database carries the same root_token is the same journal
 * (folder renamed and re-picked, or an install re-pointed at its old
 * folder), so no question is asked. A target with a DIFFERENT database is
 * a collision the user must resolve; an empty target offers the choice.
 */
internal object JournalRelocation {

    private val ownedDirs = setOf("archive", "pending", "failed", "backups")

    enum class What { NONE, SAME_ROOT, RELOCATE, COLLISION }

    data class Analysis(val what: What, val trips: Int = 0, val targetTrips: Int = 0)

    fun analyze(oldUri: String, newUri: String): Analysis {
        if (oldUri.isEmpty() || oldUri == newUri) return Analysis(What.NONE)
        val oldDir = JournalPath.rootDir(oldUri) ?: return Analysis(What.NONE)
        val newDir = JournalPath.rootDir(newUri)
        // Trees outside primary storage cannot host the database anyway;
        // plain switch, nothing to relocate.
        if (newDir == null) return Analysis(What.NONE)
        if (oldDir.absolutePath == newDir.absolutePath) return Analysis(What.SAME_ROOT)
        if (!hasJournalContent(oldDir)) return Analysis(What.NONE)
        val trips = countTrips(File(oldDir, TripDatabase.DB_FILE_NAME))
        val newDb = File(newDir, TripDatabase.DB_FILE_NAME)
        return when {
            newDb.isFile && readRootToken(newDir) == readRootToken(oldDir) ->
                Analysis(What.SAME_ROOT)
            newDb.isFile || hasJournalContent(newDir) ->
                Analysis(What.COLLISION, trips, countTrips(newDb))
            else -> Analysis(What.RELOCATE, trips)
        }
    }

    private fun hasJournalContent(dir: File): Boolean =
        dir.listFiles()?.any { it.name in ownedDirs || it.name.startsWith(TripDatabase.DB_FILE_NAME) } == true

    private fun countTrips(db: File): Int = runCatching {
        if (!db.isFile) return 0
        SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT COUNT(*) FROM trips", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
    }.getOrDefault(0)

    private fun readRootToken(dir: File): String? = runCatching {
        val db = File(dir, TripDatabase.DB_FILE_NAME)
        if (!db.isFile) return null
        SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT value FROM meta WHERE key='root_token'", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }
    }.getOrNull()

    data class Outcome(val moved: List<String>, val failed: List<String>) {
        val clean get() = failed.isEmpty()
    }

    /**
     * Copy every journal entry into the target (trip folders first, the
     * database LAST), and for a move only then delete the sources, and
     * only after the copied database passes an integrity check. An
     * interrupted run is idempotent: re-running copies over the partial
     * destination again, and the copied database lists what landed.
     * [copy] keeps the sources even on success (two independent stores).
     */
    suspend fun execute(oldUri: String, newUri: String, copy: Boolean): Outcome =
        withContext(Dispatchers.IO) {
            val oldDir = JournalPath.rootDir(oldUri) ?: return@withContext Outcome(emptyList(), listOf("old folder"))
            val newDir = JournalPath.rootDir(newUri) ?: return@withContext Outcome(emptyList(), listOf("new folder"))
            val entries = oldDir.listFiles()?.filter {
                it.name in ownedDirs || it.name.startsWith(TripDatabase.DB_FILE_NAME)
            }?.sortedBy { it.name.startsWith(TripDatabase.DB_FILE_NAME) } // database last
            ?: emptyList()
            val moved = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (entry in entries) {
                runCatching { transfer(entry, File(newDir, entry.name)) }
                    .onSuccess { moved += entry.name }
                    .onFailure { failed += entry.name }
            }
            if (!copy) {
                // The database must arrive intact before ANY source is
                // deleted, and a move with failed entries deletes nothing:
                // the source stays complete, the (partial) copy in the
                // destination is ignored until a re-run finishes the job.
                val landed = File(newDir, TripDatabase.DB_FILE_NAME)
                if (failed.isEmpty() && landed.isFile && validateDb(landed)) {
                    entries.forEach { deleteRecursively(it) }
                } else {
                    if (!landed.isFile || !validateDb(landed)) failed += "database verification"
                }
            }
            Outcome(moved.distinct(), failed.distinct())
        }

    private fun validateDb(db: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("PRAGMA integrity_check", null).use { c ->
                c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
            } && it.rawQuery("SELECT COUNT(*) FROM trips", null).use { c -> c.moveToFirst() }
        }
    }.getOrDefault(false)

    private fun deleteRecursively(file: File): Boolean {
        var ok = true
        file.listFiles()?.forEach { child -> ok = deleteRecursively(child) && ok }
        return (file.delete() || !file.exists()) && ok
    }

    /** Recursive copy; existing targets are replaced, directories merge. */
    private fun transfer(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.isDirectory && !target.mkdirs()) error("cannot create ${target.name}")
            // A null listing means the directory cannot be read at all
            // (foreign-owned file): that must fail, never masquerade as
            // an empty folder that copied fine.
            val children = source.listFiles() ?: error("cannot read ${source.name}")
            children.forEach { child -> transfer(child, File(target, child.name)) }
            return
        }
        if (target.isFile) target.delete()
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            runCatching { target.delete() } // never leave a partial copy
            throw e
        }
    }
}
