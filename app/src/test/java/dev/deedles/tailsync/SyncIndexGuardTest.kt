package dev.deedles.tailsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncIndexGuardTest {

    @Test
    fun firstBind_emptyState_writesMarkerWithoutClearing() {
        val state = File.createTempFile("state", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val cleared = SyncIndexGuard.reconcileSyncRoot(state, "/sync/a")
            assertFalse(cleared)
            assertEquals(
                "/sync/a",
                File(state, SyncIndexGuard.ROOT_MARKER).readText().trim(),
            )
        } finally {
            state.deleteRecursively()
        }
    }

    @Test
    fun missingMarkerWithIndex_clearsIndex() {
        val state = File.createTempFile("state", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val index = File(state, SyncIndexGuard.INDEX_FILE)
            index.writeText("{}")
            val tsnet = File(state, "tsnet").apply { mkdirs() }
            File(tsnet, "tailscaled.state").writeText("keep-me")
            val cleared = SyncIndexGuard.reconcileSyncRoot(state, "/sync/a")
            assertTrue(cleared)
            assertFalse(index.exists())
            assertTrue(File(tsnet, "tailscaled.state").isFile)
            assertEquals(
                "/sync/a",
                File(state, SyncIndexGuard.ROOT_MARKER).readText().trim(),
            )
        } finally {
            state.deleteRecursively()
        }
    }

    @Test
    fun syncRootChange_deletesIndexKeepsTsnetDir() {
        val state = File.createTempFile("state", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val index = File(state, SyncIndexGuard.INDEX_FILE)
            index.writeText("""{"entries":[]}""")
            val tsnet = File(state, "tsnet").apply { mkdirs() }
            File(tsnet, "tailscaled.state").writeText("keep-me")
            File(state, SyncIndexGuard.ROOT_MARKER).writeText("/sync/old")

            val cleared = SyncIndexGuard.reconcileSyncRoot(state, "/sync/new")
            assertTrue(cleared)
            assertFalse(index.exists())
            assertTrue(File(tsnet, "tailscaled.state").isFile)
            assertEquals(
                "/sync/new",
                File(state, SyncIndexGuard.ROOT_MARKER).readText().trim(),
            )
        } finally {
            state.deleteRecursively()
        }
    }

    @Test
    fun sameRoot_doesNotDeleteIndex() {
        val state = File.createTempFile("state", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val index = File(state, SyncIndexGuard.INDEX_FILE)
            index.writeText("{}")
            File(state, SyncIndexGuard.ROOT_MARKER).writeText("/sync/a")
            assertFalse(SyncIndexGuard.reconcileSyncRoot(state, "/sync/a"))
            assertTrue(index.isFile)
        } finally {
            state.deleteRecursively()
        }
    }

    @Test
    fun deleteIndexFiles_removesAtomicTemps() {
        val state = File.createTempFile("state", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            File(state, SyncIndexGuard.INDEX_FILE).writeText("{}")
            val tmp = File(state, ".tailsync-write-abc.tmp")
            tmp.writeText("partial")
            SyncIndexGuard.deleteIndexFiles(state)
            assertFalse(File(state, SyncIndexGuard.INDEX_FILE).exists())
            assertFalse(tmp.exists())
        } finally {
            state.deleteRecursively()
        }
    }
}
