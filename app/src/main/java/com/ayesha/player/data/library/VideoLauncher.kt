package com.ayesha.player.data.library

import com.ayesha.player.core.util.PathUtil
import com.ayesha.player.data.model.VideoFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * What every screen does before handing a video to the player (home_screen.dart,
 * library_screen.dart and folder_videos_screen.dart each had a copy).
 */
@Singleton
class VideoLauncher @Inject constructor(
    private val history: PlaybackHistory,
    private val library: LibraryRepository,
) {
    /**
     * The resume point to offer, or null to start from the beginning. A position within 5 s
     * of a known end counts as finished and is cleared.
     */
    suspend fun resumePointFor(video: VideoFile, knownDuration: Duration? = video.duration): Duration? {
        val saved = history.loadPosition(video.path) ?: return null
        val total = knownDuration?.inWholeMilliseconds ?: 0
        if (total > 0 && total - saved.inWholeMilliseconds < PositionRules.NEAR_END_MS) {
            history.clearPosition(video.path)
            return null
        }
        return saved
    }

    /** Records the open: bumps it in recents and clears its "NEW" badge. */
    suspend fun recordOpen(video: VideoFile) {
        history.addRecent(video)
        library.markSeen(video.path)
    }

    /** The folder a video sits in, if that folder is in the library: the playlist for next/previous. */
    fun playlistFolderFor(path: String): String? = PathUtil.parent(path).takeIf { library.folder(it) != null }
}
