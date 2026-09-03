package dev.deedles.tailsync

import java.io.File

/**
 * Guards against reusing a sync [index.json] after the sync root changes.
 *
 * The Go engine treats live index entries that are missing on disk as offline
 * deletions (tombstones) and notifies peers. Changing [UserSettings.syncDir]
 * while keeping the same [UserSettings.stateDir] would otherwise look like a
 * mass delete of the previous tree and can wipe peers.
 *
 * tsnet state under `stateDir/tsnet` is left untouched.
 */
object SyncIndexGuard {

    const val INDEX_FILE = "index.json"
    const val ROOT_MARKER = "android-sync-root"

    /**
     * If [stateDir] has an index bound to a different sync root than
     * [syncDirAbsolute], delete the index (and write leftovers). Always
     * refreshes [ROOT_MARKER] to [syncDirAbsolute].
     *
     * @return true when an index clear was performed due to a root mismatch
     */
    fun reconcileSyncRoot(stateDir: File, syncDirAbsolute: String): Boolean {
        val sync = syncDirAbsolute.trim()
        if (sync.isEmpty()) return false

        val marker = File(stateDir, ROOT_MARKER)
        val previous = readMarker(marker)
        val indexExists = File(stateDir, INDEX_FILE).isFile
        // Unknown binding (upgrade / no marker) with an existing index is also
        // unsafe: the tree on disk may not match the index.
        val mismatch = when {
            previous != null && previous != sync -> true
            previous == null && indexExists -> true
            else -> false
        }
        if (mismatch) {
            deleteIndexFiles(stateDir)
        }
        writeMarker(marker, sync)
        return mismatch
    }

    /**
     * Deletes the sync index under [stateDir] without touching tsnet state.
     * Also used when settings persist a sync-dir change before the next start.
     */
    fun deleteIndexFiles(stateDir: File) {
        if (!stateDir.exists()) return
        File(stateDir, INDEX_FILE).delete()
        // atomicfile leftovers from a crashed Save in this directory
        stateDir.listFiles()?.forEach { f ->
            if (f.isFile &&
                f.name.startsWith(".tailsync-write-") &&
                f.name.endsWith(".tmp")
            ) {
                f.delete()
            }
        }
    }

    private fun readMarker(marker: File): String? {
        if (!marker.isFile) return null
        return try {
            marker.readText().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMarker(marker: File, syncDirAbsolute: String) {
        try {
            marker.parentFile?.mkdirs()
            marker.writeText(syncDirAbsolute)
        } catch (_: Exception) {
            // Best-effort; next start still compares when readable.
        }
    }
}
