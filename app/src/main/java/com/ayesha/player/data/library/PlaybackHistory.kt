package com.ayesha.player.data.library

import com.ayesha.player.core.util.PathUtil
import com.ayesha.player.data.db.PositionDao
import com.ayesha.player.data.db.PositionEntity
import com.ayesha.player.data.db.RecentDao
import com.ayesha.player.data.db.RecentEntity
import com.ayesha.player.data.db.SQL_CHUNK
import com.ayesha.player.data.media.DurationRepository
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.model.VideoFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class ContinueWatchingItem(val video: VideoFile, val position: Duration, val duration: Duration?)

/** A folder's most recently modified video that has a saved resume point (folder_resume.dart). */
data class FolderResume(val video: VideoFile, val position: Duration)

/** Rules from position_service.dart, kept pure for tests. */
object PositionRules {
    /** Positions under 5 s aren't worth resuming. */
    const val MIN_SAVE_MS = 5_000L

    /** Within 5 s of the end counts as finished: the saved position is cleared. */
    const val NEAR_END_MS = 5_000L

    enum class Action { Ignore, Clear, Save }

    fun actionFor(positionMs: Long, durationMs: Long): Action = when {
        durationMs <= 0 || positionMs < MIN_SAVE_MS -> Action.Ignore
        durationMs - positionMs < NEAR_END_MS -> Action.Clear
        else -> Action.Save
    }
}

/**
 * Resume positions and recently opened videos: position_service.dart, recent_files_service.dart
 * and the Continue Watching / folder-resume joins from library_screen.dart.
 */
@Singleton
class PlaybackHistory @Inject constructor(
    private val positions: PositionDao,
    private val recents: RecentDao,
    private val durations: DurationRepository,
) {
    // ── Positions ────────────────────────────────────────────────────────────

    suspend fun savePosition(path: String, position: Duration, duration: Duration) {
        val ms = position.inWholeMilliseconds
        val total = duration.inWholeMilliseconds
        when (PositionRules.actionFor(ms, total)) {
            PositionRules.Action.Ignore -> Unit
            PositionRules.Action.Clear -> io { positions.delete(path) }
            PositionRules.Action.Save -> {
                io { positions.upsert(PositionEntity(path, ms, System.currentTimeMillis())) }
                durations.save(path, duration)
            }
        }
    }

    suspend fun loadPosition(path: String): Duration? =
        io { positions.get(path) }?.takeIf { it >= PositionRules.MIN_SAVE_MS }?.milliseconds

    suspend fun clearPosition(path: String) = io { positions.delete(path) }

    suspend fun renamePosition(oldPath: String, newPath: String) =
        io { positions.rename(oldPath, newPath, System.currentTimeMillis()) }

    /** Resume points for many paths in one go. */
    suspend fun loadPositions(paths: List<String>): Map<String, Duration> = io {
        val out = HashMap<String, Duration>()
        paths.chunked(SQL_CHUNK).forEach { chunk ->
            positions.getChunk(chunk).forEach { e ->
                if (e.positionMs >= PositionRules.MIN_SAVE_MS) out[e.path] = e.positionMs.milliseconds
            }
        }
        out
    }

    // ── Recents ──────────────────────────────────────────────────────────────

    /** Most recent first; videos whose file is gone are left out (not deleted). */
    suspend fun recents(): List<VideoFile> = io {
        recents.all().map { it.toVideoFile() }.filter { File(it.path).exists() }
    }

    /** Adds or bumps [video] to the front, keeping at most 20. */
    suspend fun addRecent(video: VideoFile) =
        io { recents.addAndTrim(RecentEntity.from(video, System.currentTimeMillis()), MAX_RECENTS) }

    suspend fun removeRecent(path: String) = io { recents.delete(path) }
    suspend fun clearRecents() = io { recents.clear() }

    // ── Joins ────────────────────────────────────────────────────────────────

    /**
     * Up to 10 of the 20 latest recents that still have a resume point. Finished videos drop
     * off by themselves, because their position is cleared near the end.
     */
    suspend fun continueWatching(): List<ContinueWatchingItem> {
        val toCheck = recents().take(MAX_RECENTS)
        val saved = loadPositions(toCheck.map { it.path })
        return toCheck.mapNotNull { v ->
            val pos = saved[v.path] ?: return@mapNotNull null
            ContinueWatchingItem(v, pos, v.duration ?: durations.getCached(v.path))
        }.take(MAX_CONTINUE_WATCHING)
    }

    suspend fun folderResume(folder: VideoFolder): FolderResume? {
        val saved = loadPositions(folder.videos.map { it.path })
        val best = folder.videos.filter { it.path in saved }.maxByOrNull { it.modifiedMs } ?: return null
        return FolderResume(best, saved.getValue(best.path))
    }

    /** The folder a video belongs to, as the playlist for next/previous. */
    fun playlistFor(path: String, folders: List<VideoFolder>): Pair<List<VideoFile>, Int> {
        val dir = PathUtil.parent(path)
        val playlist = folders.firstOrNull { it.path == dir }?.videos.orEmpty()
        return playlist to playlist.indexOfFirst { it.path == path }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val MAX_RECENTS = 20
        const val MAX_CONTINUE_WATCHING = 10
    }
}
