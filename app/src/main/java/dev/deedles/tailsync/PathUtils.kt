package dev.deedles.tailsync

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Helpers for mapping user-selected storage locations to absolute paths the
 * Go engine can open. With [android.Manifest.permission.MANAGE_EXTERNAL_STORAGE]
 * (all-files access), tree URIs for primary and common secondary volumes resolve
 * to real filesystem paths.
 *
 * Sync root must be an absolute, process-writable path. State/index stays in
 * app-private storage and is not the product sync destination.
 *
 * Volume path mapping is best-effort:
 * - primary → `/storage/emulated/0` (standard on modern devices; minSdk 37)
 * - other volume ids → `/storage/<id>`
 * Unresolvable or non-writable paths fail closed without inventing a private sync root.
 */
object PathUtils {

    /**
     * Best-effort conversion of a document tree URI to a filesystem path.
     * Works for primary external storage and typical secondary volume tree URIs
     * when the app has all-files access; returns null when unresolvable.
     */
    fun treeUriToAbsolutePath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") {
            return null
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        return treeDocumentIdToAbsolutePath(docId)
    }

    /**
     * Maps a Storage Access Framework tree document id (`volume:relative/path`)
     * to an absolute filesystem path. Pure for unit testing.
     */
    fun treeDocumentIdToAbsolutePath(docId: String): String? {
        val parts = docId.split(":", limit = 2)
        if (parts.size < 2) return null
        val volume = parts[0]
        val relative = parts[1].trimStart('/')
        if (volume.isBlank()) return null
        return when {
            volume.equals("primary", ignoreCase = true) ->
                // Best-effort primary root; not derived from StorageManager (keeps pure mapping).
                File("/storage/emulated/0", relative).absolutePath
            else ->
                // Secondary volumes (SD cards, etc.): /storage/<uuid>/...
                File("/storage/$volume", relative).absolutePath
        }
    }

    /** Creates [path] if missing. Intentional create — not used during pure validation. */
    fun ensureDir(path: String): File {
        val dir = File(path)
        if (path.isNotBlank() && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * True when [path] is absolute and the process can write a directory there.
     * Does **not** create directories (no mkdirs). Missing paths are accepted only
     * when an existing ancestor is a writable directory (caller should [ensureDir]
     * after validation commits).
     */
    fun isAbsoluteWritable(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.isAbsolute) return false
        return try {
            if (file.exists()) {
                file.isDirectory && file.canWrite()
            } else {
                // Non-creating probe: nearest existing ancestor must be writable.
                var ancestor = file.parentFile
                while (ancestor != null && !ancestor.exists()) {
                    ancestor = ancestor.parentFile
                }
                ancestor != null && ancestor.isDirectory && ancestor.canWrite()
            }
        } catch (_: SecurityException) {
            false
        }
    }
}
