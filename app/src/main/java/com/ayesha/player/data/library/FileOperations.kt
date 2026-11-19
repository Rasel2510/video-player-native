package com.ayesha.player.data.library

import android.content.Context
import android.media.MediaScannerConnection
import com.ayesha.player.core.util.PathUtil
import com.ayesha.player.data.media.DurationRepository
import com.ayesha.player.data.media.ThumbnailRepository
import com.ayesha.player.data.model.VideoFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Filename rules from folder_videos_screen.dart's rename flow. Pure, so unit-tested. */
object FileNameRules {
    // Windows device names: rejected as a base name on FAT/exFAT SD cards.
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** "Movie (1)" and ".mkv" for "Movie (1).mkv". The extension is kept fixed on rename. */
    fun splitName(name: String): Pair<String, String> {
        val ext = PathUtil.extension(name)
        return (if (ext.isEmpty()) name else name.dropLast(ext.length)) to ext
    }

    /**
     * Replaces characters that are illegal on common filesystems, strips trailing dots and
     * spaces, and prefixes '_' to a leading dot (hidden file) or a reserved device name.
     * Returns null when nothing usable is left.
     */
    fun sanitizeBaseName(input: String): String? {
        var s = input.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("[. ]+$"), "")
            .trim()
        if (s.isEmpty()) return null
        if (s.startsWith('.') || s.uppercase() in reserved) s = "_$s"
        return s
    }
}

sealed interface RenameResult {
    data class Renamed(val video: VideoFile) : RenameResult
    data object Unchanged : RenameResult
    data object AlreadyExists : RenameResult
    data object Failed : RenameResult
}

data class TransferResult(val succeeded: Int, val skipped: Int, val movedPaths: List<String>)

/**
 * File actions from folder_videos_screen.dart. Each one also keeps the caches consistent:
 * a rename or move carries the resume point, duration and thumbnail over to the new path;
 * a delete drops them.
 */
@Singleton
class FileOperations @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val history: PlaybackHistory,
    private val durations: DurationRepository,
    private val thumbnails: ThumbnailRepository,
) {
    /** Deletes files (missing ones count as gone) and returns the paths now absent. */
    suspend fun delete(paths: Collection<String>): List<String> = withContext(Dispatchers.IO) {
        val gone = paths.filter { p ->
            val f = File(p)
            try {
                !f.exists() || f.delete()
            } catch (_: SecurityException) {
                false
            }
        }
        gone.forEach { forget(it) }
        library.removeVideos(gone)
        rescan(gone)
        gone
    }

    suspend fun rename(video: VideoFile, newBaseName: String): RenameResult = withContext(Dispatchers.IO) {
        val (oldBase, ext) = FileNameRules.splitName(video.name)
        val trimmed = newBaseName.trim()
        if (trimmed.isEmpty() || trimmed == oldBase) return@withContext RenameResult.Unchanged
        val safe = FileNameRules.sanitizeBaseName(trimmed) ?: return@withContext RenameResult.Unchanged
        val newName = safe + ext
        val newPath = "${PathUtil.parent(video.path)}/$newName"
        if (newPath == video.path) return@withContext RenameResult.Unchanged
        if (File(newPath).exists()) return@withContext RenameResult.AlreadyExists
        val ok = try {
            File(video.path).renameTo(File(newPath))
        } catch (_: SecurityException) {
            false
        }
        if (!ok) return@withContext RenameResult.Failed

        carryOver(video.path, newPath)
        val renamed = video.copy(path = newPath, name = newName)
        library.renameVideo(video.path, renamed)
        rescan(listOf(video.path, newPath))
        RenameResult.Renamed(renamed)
    }

    /**
     * Moves or copies [videos] into [destFolder]. A per-file failure (name already taken,
     * permission) is skipped instead of aborting the batch. A move across volumes
     * (internal <-> SD card) falls back to copy + delete.
     */
    suspend fun transfer(videos: List<VideoFile>, destFolder: String, move: Boolean): TransferResult =
        withContext(Dispatchers.IO) {
            var ok = 0
            var skipped = 0
            val moved = mutableListOf<String>()
            val touched = mutableListOf<String>()
            val created = mutableListOf<String>()
            for (v in videos) {
                val target = File(destFolder, v.name)
                if (target.path == v.path || target.exists()) {
                    skipped++
                    continue
                }
                try {
                    val src = File(v.path)
                    if (move) {
                        if (!src.renameTo(target)) {
                            src.copyTo(target)
                            if (!src.delete()) throw IOException("copied but couldn't delete ${v.path}")
                        }
                        carryOver(v.path, target.path)
                        moved += v.path
                    } else {
                        src.copyTo(target)
                    }
                    touched += v.path
                    touched += target.path
                    created += target.path
                    ok++
                } catch (_: Exception) {
                    // A half-written copy must not linger and show up as a broken video. Only
                    // remove it while the source still exists, so no data can be lost.
                    if (target.exists() && File(v.path).exists()) target.delete()
                    skipped++
                }
            }
            if (moved.isNotEmpty()) library.removeVideos(moved)
            // The new copies appear in the destination folder at once (no full rescan).
            library.insertVideos(created.mapNotNull { VideoFile.fromFile(File(it)) })
            rescan(touched)
            TransferResult(ok, skipped, moved)
        }

    suspend fun clearResume(paths: Collection<String>) {
        paths.forEach { history.clearPosition(it) }
    }

    private suspend fun carryOver(oldPath: String, newPath: String) {
        history.renamePosition(oldPath, newPath)
        durations.rename(oldPath, newPath)
        thumbnails.rename(oldPath, newPath)
    }

    private suspend fun forget(path: String) {
        history.clearPosition(path)
        thumbnails.remove(path)
        durations.remove(path)
    }

    /** Lets MediaStore index the change now, instead of whenever it next notices on its own. */
    private fun rescan(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
    }
}
