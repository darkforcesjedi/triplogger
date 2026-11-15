package com.mallner.triplogger

/**
 * Pure diff between trip rows in the database and journal folders on disk
 * (TODO "Trip metadata database" reconciliation). The store applies the
 * plan; this function only classifies, so it is unit-testable.
 *
 * Folder truth only counts folders that actually contain journal files:
 * empty leftover directories (interrupted SAF deletes, import scratch) are
 * reported for cleanup instead of resurrecting rows.
 */
object Reconciliation {

    data class TripRowLite(val folder: String, val state: String, val rawState: String)

    /** One journal folder found on disk. */
    data class DiskFolder(val name: String, val state: String, val hasJournalFiles: Boolean)

    data class Plan(
        /** Folder names (with their on-disk state) that have no DB row. */
        val importFolders: List<DiskFolder>,
        /** Folders whose row says raw_state 'present' but the folder is gone. */
        val markMissing: List<String>,
        /** Folders whose row is flagged 'missing' but came back (restore). */
        val revive: List<String>,
        /** folder -> state the folder actually lives in now (moved externally). */
        val stateDrift: List<Pair<String, String>>,
        /** Empty folders with no row: safe to delete, never resurrected. */
        val emptyStrays: List<String>
    )

    /**
     * @param rows all live trip rows (folder, state, raw_state).
     * @param folders every journal folder across pending/archive/failed,
     *   where hasJournalFiles means it contains at least one gps, obd or
     *   odo CSV file.
     */
    fun reconcile(rows: List<TripRowLite>, folders: List<DiskFolder>): Plan {
        val rowByFolder = rows.associateBy { it.folder }
        val diskByName = folders.filter { it.hasJournalFiles }.associateBy { it.name }
        val emptyStrays = folders.filter { !it.hasJournalFiles && it.name !in rowByFolder }
            .map { it.name }

        val importFolders = folders.filter { it.hasJournalFiles && it.name !in rowByFolder }
        val markMissing = rows
            .filter { it.rawState == TripDatabase.RAW_PRESENT && it.folder !in diskByName }
            .map { it.folder } // store maps folder -> id
        val revive = rows
            .filter { it.rawState == TripDatabase.RAW_MISSING && it.folder in diskByName }
            .map { it.folder }
        val stateDrift = rows.mapNotNull { row ->
            val disk = diskByName[row.folder]
            // Only meaningful while the row still claims to own a present folder.
            if (disk != null && row.rawState == TripDatabase.RAW_PRESENT && disk.state != row.state) {
                row.folder to disk.state
            } else null
        }

        return Plan(importFolders, markMissing, revive, stateDrift, emptyStrays)
    }
}
