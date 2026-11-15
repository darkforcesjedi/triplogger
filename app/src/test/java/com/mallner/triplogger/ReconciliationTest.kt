package com.mallner.triplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder-vs-row diff for startup reconciliation: re-import strays, flag
 * externally deleted journals, revive restored ones, keep purged trips
 * purged, and never resurrect empty leftover directories.
 */
class ReconciliationTest {

    private fun row(folder: String, state: String = "archive", raw: String = "present") =
        Reconciliation.TripRowLite(folder, state, raw)

    private fun dir(name: String, state: String = "archive", journal: Boolean = true) =
        Reconciliation.DiskFolder(name, state, journal)

    @Test
    fun folderWithoutRowIsImported() {
        val plan = Reconciliation.reconcile(
            rows = listOf(row("known")),
            folders = listOf(dir("known"), dir("stray"))
        )
        assertEquals(listOf("stray"), plan.importFolders.map { it.name })
        assertTrue(plan.markMissing.isEmpty())
    }

    @Test
    fun presentRowWithoutFolderIsMarkedMissing() {
        val plan = Reconciliation.reconcile(
            rows = listOf(row("gone"), row("still")),
            folders = listOf(dir("still"))
        )
        assertEquals(listOf("gone"), plan.markMissing)
        assertTrue(plan.revive.isEmpty())
    }

    @Test
    fun restoredFolderRevivesMissingRow() {
        val plan = Reconciliation.reconcile(
            rows = listOf(row("back", raw = "missing"), row("still", raw = "missing")),
            folders = listOf(dir("back"))
        )
        assertEquals(listOf("back"), plan.revive)
        // Rows already flagged missing need no re-marking.
        assertTrue(plan.markMissing.isEmpty())
    }

    @Test
    fun purgedRowsStayPurgedWithoutTheirFolders() {
        val plan = Reconciliation.reconcile(
            rows = listOf(
                row("purgedR", raw = "purged_retention"),
                row("purgedM", raw = "purged_manual")
            ),
            folders = emptyList()
        )
        assertTrue(plan.markMissing.isEmpty())
        assertTrue(plan.revive.isEmpty())
    }

    @Test
    fun purgedRowWithFolderReappearingIsNotRevived() {
        // A folder reappearing under a purged_* state means the user put a
        // copy back; that is an import/re-link decision, not automatic life.
        val plan = Reconciliation.reconcile(
            rows = listOf(row("again", raw = "purged_retention")),
            folders = listOf(dir("again"))
        )
        assertTrue(plan.revive.isEmpty())
        assertTrue(plan.markMissing.isEmpty())
    }

    @Test
    fun stateDriftDetectedWhenFolderMovedExternally() {
        val plan = Reconciliation.reconcile(
            rows = listOf(row("moved", state = "pending")),
            folders = listOf(dir("moved", state = "archive"))
        )
        assertEquals(listOf("moved" to "archive"), plan.stateDrift)
    }

    @Test
    fun emptyStrayFoldersReportedForCleanup() {
        val plan = Reconciliation.reconcile(
            rows = listOf(row("known")),
            folders = listOf(dir("known"), dir("empty", journal = false))
        )
        assertTrue(plan.importFolders.isEmpty())
        assertEquals(listOf("empty"), plan.emptyStrays)
    }

    @Test
    fun healthyStoreProducesEmptyPlan() {
        val plan = Reconciliation.reconcile(
            rows = listOf(row("a"), row("b", state = "pending", raw = "purged_retention")),
            folders = listOf(dir("a"), dir("b", state = "pending"))
        )
        assertTrue(plan.importFolders.isEmpty())
        assertTrue(plan.markMissing.isEmpty())
        assertTrue(plan.revive.isEmpty())
        assertTrue(plan.stateDrift.isEmpty())
        assertTrue(plan.emptyStrays.isEmpty())
    }
}
