package dev.deedles.tailsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathUtilsTest {

    @Test
    fun treeDocumentId_primaryRoot() {
        val path = PathUtils.treeDocumentIdToAbsolutePath("primary:")
        assertEquals("/storage/emulated/0", path)
    }

    @Test
    fun treeDocumentId_primarySubdir() {
        val path = PathUtils.treeDocumentIdToAbsolutePath("primary:Download/Tailsync")
        assertEquals("/storage/emulated/0/Download/Tailsync", path)
    }

    @Test
    fun treeDocumentId_secondaryVolume() {
        val path = PathUtils.treeDocumentIdToAbsolutePath("ABCD-1234:Media")
        assertEquals("/storage/ABCD-1234/Media", path)
    }

    @Test
    fun treeDocumentId_invalid() {
        assertNull(PathUtils.treeDocumentIdToAbsolutePath("nocolon"))
        assertNull(PathUtils.treeDocumentIdToAbsolutePath(""))
    }

    @Test
    fun isAbsoluteWritable_tempDir() {
        val dir = File.createTempFile("tailsync", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            assertTrue(PathUtils.isAbsoluteWritable(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun isAbsoluteWritable_rejectsRelative() {
        assertTrue(!PathUtils.isAbsoluteWritable("relative/path"))
        assertTrue(!PathUtils.isAbsoluteWritable(""))
    }
}
