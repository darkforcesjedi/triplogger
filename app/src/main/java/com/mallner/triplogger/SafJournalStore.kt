package com.mallner.triplogger

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * JournalStore inside a user-selected SAF tree (Storage Access Framework
 * document tree) with pending/ success/ failed/ subfolders.
 *
 * Note: the SAF provider must support held-open appendable streams; local
 * storage and SD cards do, cloud providers (e.g. Google Drive) may not keep
 * data durable until close, so pick a local folder for the temp location.
 */
class SafJournalStore(private val context: Context, treeUri: Uri) : JournalStore {

    companion object {
        private const val TAG = "SafJournalStore"
    }

    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid journal tree")

    private fun subDir(name: String): DocumentFile =
        root.findFile(name) ?: root.createDirectory(name) ?: throw IOException("Cannot create $name in journal tree")

    override fun createTrip(tripId: String, startMs: Long, pids: List<String>): LiveJournal {
        val pending = subDir("pending")
        pending.findFile(tripId)?.delete()
        val dir = pending.createDirectory(tripId) ?: throw IOException("Cannot create trip directory $tripId")

        writeFile(dir, "trip.properties",
            "trip_id=$tripId\nstart_ms=$startMs\npids=${pids.joinToString(",")}\n")

        // Segment files are created lazily: index 0 when the journal opens,
        // higher indices on each split().
        return LiveJournal(tripId, startMs, pids) { index ->
            val gpsFile = dir.createFile("text/csv", segmentName("gps", index))
                ?: throw IOException("Cannot create gps segment $index")
            val obdFile = dir.createFile("text/csv", segmentName("obd", index))
                ?: throw IOException("Cannot create obd segment $index")
            Pair(openAppendStream(gpsFile), openAppendStream(obdFile))
        }
    }

    private fun openAppendStream(doc: DocumentFile) =
        context.contentResolver.openOutputStream(doc.uri, "wa")
            ?: context.contentResolver.openOutputStream(doc.uri, "w")
            ?: throw IOException("Cannot open output stream for ${doc.name}")

    private fun writeFile(dir: DocumentFile, name: String, text: String) {
        dir.findFile(name)?.delete()
        val doc = dir.createFile("text/plain", name) ?: throw IOException("Cannot create $name")
        context.contentResolver.openOutputStream(doc.uri, "w")?.use { it.write(text.toByteArray()) }
            ?: throw IOException("Cannot write $name")
    }

    override fun unfinishedTrips(): List<OrphanTrip> {
        val trips = ArrayList<OrphanTrip>()
        for (sub in listOfNotNull(root.findFile("pending"), root.findFile("failed"))) {
            if (!sub.isDirectory) continue
            sub.listFiles()
                .filter { it.isDirectory && it.findFile("trip.properties") != null }
                .sortedBy { it.name }
                .forEach { trips.add(SafOrphanTrip(context, it)) }
        }
        return trips
    }

    override fun relocate(trip: OrphanTrip, toSuccess: Boolean) {
        val source = (trip as SafOrphanTrip).dir
        val targetParent = subDir(if (toSuccess) "success" else "failed")
        targetParent.findFile(source.name ?: "trip")?.delete()
        val target = targetParent.createDirectory(source.name ?: "trip")
            ?: throw IOException("Cannot create target directory for relocation")
        try {
            for (doc in source.listFiles()) {
                if (!doc.isFile) continue
                val bytes = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() } ?: continue
                val copy = target.createFile(doc.type ?: "text/plain", doc.name ?: continue)
                    ?: throw IOException("Cannot copy ${doc.name}")
                context.contentResolver.openOutputStream(copy.uri, "w")?.use { it.write(bytes) }
                    ?: throw IOException("Cannot write ${doc.name}")
                doc.delete()
            }
            source.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Relocation of ${source.name} failed", e)
            throw e
        }
    }

    override fun delete(trip: OrphanTrip) {
        (trip as SafOrphanTrip).dir.delete()
    }

    override fun orphanOf(journal: LiveJournal): OrphanTrip {
        val dir = subDir("pending").findFile(journal.tripId)
            ?: throw IOException("Trip ${journal.tripId} not found in pending")
        return SafOrphanTrip(context, dir)
    }
}

class SafOrphanTrip(private val context: Context, val dir: DocumentFile) : OrphanTrip(dir.name ?: "trip") {
    override fun readText(fileName: String): String? {
        val doc = dir.findFile(fileName) ?: return null
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun listFileNames(): List<String> =
        dir.listFiles().filter { it.isFile }.mapNotNull { it.name }
}
