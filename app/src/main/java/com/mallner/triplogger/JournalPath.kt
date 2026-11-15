package com.mallner.triplogger

import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Filesystem view of the SAF journal root. The app already treats the
 * selected tree as a plain directory (see SafJournalStore), and every real
 * deployment picks primary external storage, whose tree URIs are stable:
 * content://com.android.externalstorage.documents/tree/primary%3B... .
 * The DB file must live inside that folder (it travels with the trips),
 * so the URI has to resolve back to a File.
 */
object JournalPath {

    /**
     * Relative filesystem path below external storage for the decoded path
     * segments of a tree or document URI, e.g. "Android/data/.../files/Trips".
     * Tree URIs carry the doc id after "tree" with a ';' volume separator,
     * document URIs after "document" with ':'. Null when the URI is not a
     * primary external-storage tree (SD card, other providers) - callers
     * then run without the DB (v1 stance).
     */
    fun relativePathFromSegments(segments: List<String>): String? {
        val idx = segments.indexOf("document").takeIf { it >= 0 && it + 1 < segments.size }
            ?: segments.indexOf("tree").takeIf { it >= 0 && it + 1 < segments.size }
            ?: return null
        val docId = segments[idx + 1]
        val sep = docId.indexOfAny(charArrayOf(':', ';'))
        if (sep <= 0) return null
        if (docId.substring(0, sep) != "primary") return null
        val relative = docId.substring(sep + 1).replace('/', File.separatorChar)
        return relative.ifEmpty { null }
    }

    /** The journal root directory for a persisted tree URI, or null. */
    fun rootDir(treeUri: Uri): File? {
        val relative = relativePathFromSegments(treeUri.pathSegments) ?: return null
        val root = File(Environment.getExternalStorageDirectory(), relative)
        return if (root.isDirectory) root else null
    }

    /** The journal root for a tree URI string (no permission checks here). */
    fun rootDir(treeUriString: String): File? =
        runCatching { rootDir(Uri.parse(treeUriString)) }.getOrNull()
}
