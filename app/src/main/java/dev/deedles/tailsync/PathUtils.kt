package dev.deedles.tailsync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Helpers for mapping user-selected storage locations to absolute paths the
 * Go engine can open. The mobile API requires absolute, process-writable paths;
 * arbitrary SAF trees are not always resolvable to such paths on modern Android.
 */
object PathUtils {

    /**
     * Best-effort conversion of a document tree URI to a filesystem path.
     * Works for primary external storage tree URIs on many devices; returns
     * null when the path cannot be resolved safely.
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
                File("/storage/emulated/0", relative).absolutePath
            else ->
                // Secondary volumes vary by OEM; path may not be writable.
                File("/storage/$volume", relative).absolutePath
        }
    }

    fun ensureDir(path: String): File {
        val dir = File(path)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun defaultAppSyncDir(context: Context): File =
        File(context.applicationContext.filesDir, "sync")

    fun defaultAppStateDir(context: Context): File =
        File(context.applicationContext.filesDir, "tailsync-state")

    fun isAbsoluteWritable(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.isAbsolute) return false
        return try {
            if (!file.exists()) {
                file.mkdirs()
            }
            file.isDirectory && file.canWrite()
        } catch (_: SecurityException) {
            false
        }
    }
}
