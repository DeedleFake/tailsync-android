package dev.deedles.tailsync

import android.net.Uri
import android.os.Environment
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
 * - primary → process-visible external storage (`Environment.getExternalStorageDirectory()`,
 *   typically `/storage/emulated/<user>`)
 * - other volume ids → `/storage/<id>`
 * Unresolvable, traversable (`..`), or non-writable paths fail closed without
 * inventing a private sync root.
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
        // Process-visible primary volume (correct for secondary users / profiles).
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        return treeDocumentIdToAbsolutePath(docId, primaryRoot = primaryRoot)
    }

    /**
     * Maps a Storage Access Framework tree document id (`volume:relative/path`)
     * to an absolute filesystem path. Pure for unit testing.
     *
     * Rejects path segments that escape the volume root (`..`) and volume ids
     * that are not simple names. Normalizes `.` / redundant separators.
     */
    fun treeDocumentIdToAbsolutePath(
        docId: String,
        primaryRoot: String = "/storage/emulated/0",
    ): String? {
        val parts = docId.split(":", limit = 2)
        if (parts.size < 2) return null
        val volume = parts[0]
        val relative = parts[1].trimStart('/')
        if (volume.isBlank()) return null
        // Volume ids from SAF are simple (primary / UUID); reject path-like ids.
        if (volume.contains('/') || volume.contains('\\') || volume.contains("..")) {
            return null
        }
        if (relative.contains('\u0000')) return null
        for (segment in relative.split('/')) {
            if (segment == "..") return null
        }

        val rootPath = when {
            volume.equals("primary", ignoreCase = true) ->
                File(primaryRoot).absolutePath
            else ->
                // Secondary volumes (SD cards, etc.): /storage/<uuid>/...
                File("/storage/$volume").absolutePath
        }
        val joined = if (relative.isEmpty()) {
            File(rootPath).toPath()
        } else {
            File(rootPath, relative).toPath()
        }
        val normalized = joined.normalize().toAbsolutePath().toString()
        val rootNormalized = File(rootPath).toPath().normalize().toAbsolutePath().toString()
        if (normalized != rootNormalized && !normalized.startsWith("$rootNormalized/")) {
            return null
        }
        return normalized
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
