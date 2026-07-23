package dev.deedles.tailsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun treeDocumentId_secondaryNested() {
        val path = PathUtils.treeDocumentIdToAbsolutePath("XXXX-YYYY:Android/data")
        assertEquals("/storage/XXXX-YYYY/Android/data", path)
    }

    @Test
    fun treeDocumentId_invalid() {
        assertNull(PathUtils.treeDocumentIdToAbsolutePath("nocolon"))
        assertNull(PathUtils.treeDocumentIdToAbsolutePath(""))
    }

    @Test
    fun isAbsoluteWritable_existingTempDir() {
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
    fun isAbsoluteWritable_rejectsRelativeAndBlank() {
        assertFalse(PathUtils.isAbsoluteWritable("relative/path"))
        assertFalse(PathUtils.isAbsoluteWritable(""))
        assertFalse(PathUtils.isAbsoluteWritable("   "))
    }

    @Test
    fun isAbsoluteWritable_doesNotCreateMissingDir() {
        val base = File.createTempFile("tailsync", "base").apply {
            delete()
            mkdirs()
        }
        val child = File(base, "does-not-exist-yet")
        try {
            // Parent writable → validation succeeds without creating the child.
            assertTrue(PathUtils.isAbsoluteWritable(child.absolutePath))
            assertFalse(child.exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun ensureDir_createsMissing() {
        val base = File.createTempFile("tailsync", "ens").apply {
            delete()
            mkdirs()
        }
        val child = File(base, "created")
        try {
            assertFalse(child.exists())
            PathUtils.ensureDir(child.absolutePath)
            assertTrue(child.isDirectory)
        } finally {
            base.deleteRecursively()
        }
    }
}
