package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retention and naming for database snapshots (files in backups/ are the
 * truth, newest first). The snapshot/restore IO needs device SQLite and is
 * covered by device smokes.
 */
class DbBackupTest {

    private fun name(epochSec: Long) =
        DbBackup.snapshotName(1_700_000_000_000L + epochSec * 1000L)

    @Test
    fun namesSortChronologically() {
        val older = DbBackup.snapshotName(1794700000000L)
        val newer = DbBackup.snapshotName(1794800000000L)
        assertTrue(newer > older)
        assertTrue(newer.matches(Regex("""triplogger-\d{8}_\d{6}\.db""")))
    }

    @Test
    fun pruneKeepsNewestAndIgnoresForeignFiles() {
        val names = (1L..12L).map { name(it) } + listOf("readme.txt", "triplogger-not-a-snapshot.db")
        val doomed = DbBackup.prunePlan(names.shuffled(), keep = 8)
        val expected = names.take(4) // the 4 oldest timestamps
        assertEquals(expected.sorted(), doomed.sorted())
    }

    @Test
    fun pruneNoopUnderTheCap() {
        val names = (1L..8L).map { name(it) }
        assertEquals(emptyList<String>(), DbBackup.prunePlan(names, keep = 8))
        assertEquals(emptyList<String>(), DbBackup.prunePlan(emptyList(), keep = 8))
    }

    @Test
    fun restoreOfferIsWithheldWhenTheBackupCannotFixIt() {
        val newest = name(100)
        // No snapshot at all: nothing to offer.
        assertEquals(null, DbBackup.restoreOfferName(null, setOf("20261115_020936"), false))
        // Folders on disk that the backup does not contain: a hand-copied
        // journal, NOT a database problem - never offer a rollback.
        assertEquals(null, DbBackup.restoreOfferName(newest, setOf("20261115_020936"), false))
        // The backup contains one of the folders: restoring helps.
        assertEquals(newest, DbBackup.restoreOfferName(newest, setOf("20261115_020936"), true))
        // Nothing readable on disk (fresh corrupt install): trust the backup.
        assertEquals(newest, DbBackup.restoreOfferName(newest, emptySet(), false))
    }
}
