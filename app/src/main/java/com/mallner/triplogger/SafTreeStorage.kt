package com.mallner.triplogger

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * TripStorage backed by a SAF tree (the user-selected storage folder).
 * Writes go to "<name>.tmp" first; the existing file is only deleted once the
 * new content is fully written, then the temp doc is renamed into place.
 */
class SafTreeStorage(private val context: Context, treeUri: Uri) : TripStorage {

    companion object {
        private const val TAG = "SafTreeStorage"
    }

    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid storage tree")

    override fun read(name: String): ByteArray? {
        val doc = root.findFile(name) ?: return null
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading $name", e)
            null
        }
    }

    override fun write(name: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // renameTo on tree documents requires API 26; write directly on older devices
            writeDirect(name, bytes)
            return
        }
        val tmpName = "$name.tmp"
        root.findFile(tmpName)?.delete()
        val tmp = root.createFile("application/octet-stream", tmpName)
            ?: throw IOException("Could not create $tmpName in storage tree")
        try {
            context.contentResolver.openOutputStream(tmp.uri, "w")?.use { it.write(bytes) }
                ?: throw IOException("Could not open output stream for $tmpName")
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            throw IOException("Failed writing $tmpName", e)
        }
        // New content is fully on disk now; clear the old file and rename into place.
        // (If the device dies between these two steps the journal is still intact,
        // so the trip gets re-finalized on next start.)
        root.findFile(name)?.delete()
        if (!tmp.renameTo(name)) {
            // rename failed (some providers refuse to overwrite); fall back to direct write
            Log.w(TAG, "renameTo failed for $name, writing directly")
            writeDirect(name, bytes)
            return
        }
        val oldTmp = root.findFile(tmpName)
        oldTmp?.delete()
    }

    private fun writeDirect(name: String, bytes: ByteArray) {
        root.findFile(name)?.delete()
        val doc = root.createFile("application/octet-stream", name)
            ?: throw IOException("Could not create $name in storage tree")
        context.contentResolver.openOutputStream(doc.uri, "w")?.use { it.write(bytes) }
            ?: throw IOException("Could not open output stream for $name")
    }
}
