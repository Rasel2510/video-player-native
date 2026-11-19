package com.ayesha.player.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FolderScannerTest {

    @get:Rule val tmp = TemporaryFolder()
    private val scanner = FolderScanner()

    private fun video(dir: File, name: String, bytes: Int = 2048): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(ByteArray(bytes)) }
    }

    @Test fun `groups by folder, sorts case-insensitively, skips junk`() {
        val root = tmp.root
        video(File(root, "Movies"), "b.mkv")
        video(File(root, "Movies"), "A.mp4")
        video(File(root, "Movies"), "notes.txt")
        video(File(root, "Movies"), "tiny.mp4", bytes = 100) // under 1 KB: skipped
        video(File(root, "camera"), "clip.MOV")
        video(File(root, ".hidden"), "secret.mp4") // dot folder: skipped
        File(root, "Empty").mkdirs()

        val folders = scanner.scanFolders(root.path)

        // File(...).name rather than folder.name: on a Windows test host paths use '\', while
        // VideoFolder.name splits on '/' like Android (where '\' is a legal filename character).
        assertEquals(listOf("camera", "Movies"), folders.map { File(it.path).name })
        assertEquals(listOf("A.mp4", "b.mkv"), folders[1].videos.map { it.name })
        assertEquals(2048L, folders[0].videos.single().size)
    }

    @Test fun `missing root gives an empty result`() {
        assertTrue(scanner.scanFolders(File(tmp.root, "nope").path).isEmpty())
    }

    @Test fun `progress fires on multiples of 25`() {
        val dir = File(tmp.root, "Bulk")
        repeat(25) { video(dir, "v$it.mp4") }
        val reported = mutableListOf<Int>()
        scanner.scanFolders(tmp.root.path) { reported += it }
        assertEquals(listOf(25), reported)
    }

    @Test fun `quick check matches the snapshot, then notices changes`() {
        val movies = File(tmp.root, "Movies")
        video(movies, "a.mp4")
        video(movies, "b.mp4")
        video(movies, "tiny.mp4", bytes = 10) // must not count, or it would always look changed
        val roots = listOf(tmp.root.path)
        val snapshot = mapOf(movies.path to 2)

        assertFalse(scanner.hasChanged(roots, snapshot))

        video(movies, "c.mp4")
        assertTrue(scanner.hasChanged(roots, snapshot))
    }

    @Test fun `quick check notices a deleted folder`() {
        val a = File(tmp.root, "A")
        video(a, "x.mp4")
        val snapshot = mapOf(a.path to 1, File(tmp.root, "Gone").path to 3)
        assertTrue(scanner.hasChanged(listOf(tmp.root.path), snapshot))
    }

    @Test fun `storage roots map emulated to its user 0 folder`() {
        val storage = tmp.newFolder("storage")
        File(storage, "emulated/0").mkdirs()
        File(storage, "self").mkdirs()
        File(storage, "1234-ABCD").mkdirs()
        val roots = scanner.discoverStorageRoots(storage).map { File(it).relativeTo(storage).path.replace('\\', '/') }
        assertEquals(setOf("emulated/0", "1234-ABCD"), roots.toSet())
    }

    @Test fun `storage roots fall back to the default`() {
        assertEquals(listOf(FolderScanner.DEFAULT_ROOT), scanner.discoverStorageRoots(File(tmp.root, "missing")))
    }
}
