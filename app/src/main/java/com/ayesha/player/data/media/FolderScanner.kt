package com.ayesha.player.data.media

import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.model.VideoFolder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port of folder_scanner.dart plus the quick change check and storage-root discovery from
 * folders_provider.dart. Plain java.io only, so it runs in local unit tests.
 * All calls block: run them on Dispatchers.IO.
 */
@Singleton
class FolderScanner @Inject constructor() {

    /** `/storage/emulated/0` plus each mounted SD card / USB volume under `/storage`. */
    fun discoverStorageRoots(storageDir: File = File("/storage")): List<String> {
        val roots = mutableListOf<String>()
        try {
            val entries = storageDir.listFiles() ?: return listOf(DEFAULT_ROOT)
            for (entity in entries) {
                if (!entity.isDirectory || isSymlink(entity)) continue
                when (entity.name) {
                    "self" -> Unit
                    "emulated" -> File(entity, "0").takeIf { it.isDirectory }?.let { roots += it.path }
                    else -> roots += entity.path
                }
            }
        } catch (_: SecurityException) {
        }
        if (roots.isEmpty()) roots += DEFAULT_ROOT
        return roots
    }

    /**
     * Recursive walk of [rootPath], grouped by folder and sorted by name (case-insensitive).
     * Skips dot-folders, symlinks and files under 1 KB. [onProgress] fires with the running
     * total whenever it lands on a multiple of 25, as in the Dart version.
     */
    fun scanFolders(rootPath: String, onProgress: ((Int) -> Unit)? = null): List<VideoFolder> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptyList()
        val out = LinkedHashMap<String, MutableList<VideoFile>>()
        recurse(root, out, 0, onProgress)
        return LibraryGrouping.sortedFolders(out)
    }

    private fun recurse(
        dir: File,
        out: MutableMap<String, MutableList<VideoFile>>,
        runningTotal: Int,
        onProgress: ((Int) -> Unit)?,
    ): Int {
        var total = runningTotal
        val entries = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return total

        val videos = mutableListOf<VideoFile>()
        val subDirs = mutableListOf<File>()
        for (entity in entries) {
            if (isSymlink(entity)) continue
            if (entity.isDirectory) {
                if (!entity.name.startsWith('.')) subDirs += entity
            } else if (VideoFile.isVideoFile(entity.name)) {
                VideoFile.fromFile(entity)?.takeIf { it.size >= MIN_VIDEO_BYTES }?.let { videos += it }
            }
        }
        if (videos.isNotEmpty()) {
            out[dir.path] = videos
            total += videos.size
            if (total % 25 == 0) onProgress?.invoke(total)
        }
        for (sub in subDirs) total = recurse(sub, out, total, onProgress)
        return total
    }

    /**
     * True when the folder -> video-count picture on disk differs from [snapshot]: a new or
     * resized folder, or a snapshot folder that no longer has videos. Stops at the first
     * difference. Port of `_hasNewContent` / `_quickScanSync`; an empty snapshot counts as changed.
     */
    fun hasChanged(roots: List<String>, snapshot: Map<String, Int>): Boolean {
        if (snapshot.isEmpty()) return true
        val seen = HashSet<String>()
        return try {
            roots.any { quickCheck(File(it), snapshot, seen) } || snapshot.keys.any { it !in seen }
        } catch (_: Exception) {
            true
        }
    }

    /** Returns true as soon as a difference is found. */
    private fun quickCheck(dir: File, snapshot: Map<String, Int>, seen: MutableSet<String>): Boolean {
        val entries = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return false
        var count = 0
        val subs = mutableListOf<File>()
        for (entity in entries) {
            if (isSymlink(entity)) continue
            if (entity.isDirectory) {
                if (!entity.name.startsWith('.')) subs += entity
            } else if (VideoFile.isVideoFile(entity.name) && entity.length() >= MIN_VIDEO_BYTES) {
                // Same size filter as scanFolders. The Dart check counted sub-1 KB files the scan
                // skips, so a folder holding one never matched its snapshot and forced a rescan.
                count++
            }
        }
        if (count > 0) {
            seen += dir.path
            if (snapshot[dir.path] != count) return true
        }
        return subs.any { quickCheck(it, snapshot, seen) }
    }

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0"
        const val MIN_VIDEO_BYTES = 1024L

        // java.nio.file only exists from API 26; on 24-25 symlinks under /storage are rare enough to ignore.
        private val nioAvailable: Boolean by lazy {
            try {
                Class.forName("java.nio.file.Files")
                true
            } catch (_: Throwable) {
                false
            }
        }

        // Dart used listSync(followLinks: false). Following links can loop forever.
        internal fun isSymlink(f: File): Boolean =
            nioAvailable && try {
                java.nio.file.Files.isSymbolicLink(f.toPath())
            } catch (_: Exception) {
                false
            }
    }
}
