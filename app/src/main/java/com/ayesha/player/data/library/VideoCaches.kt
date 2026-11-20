package com.ayesha.player.data.library

import com.ayesha.player.data.media.DurationRepository
import com.ayesha.player.data.media.ThumbnailRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the app keeps per video path besides the file itself: resume point, recents entry,
 * duration and thumbnail (JPEG on disk + decoded bitmap in memory). File actions go through here,
 * so a delete leaves nothing behind and a rename or move keeps it all.
 */
@Singleton
class VideoCaches @Inject constructor(
    private val history: PlaybackHistory,
    private val durations: DurationRepository,
    private val thumbnails: ThumbnailRepository,
) {
    /** Drops every cache entry of deleted files. Batched: a few statements for any number of videos. */
    suspend fun forget(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val list = paths.toList()
        thumbnails.removeAll(list)
        durations.removeAll(list)
        history.forget(list)
    }

    /** A renamed or moved file keeps its resume point, duration and thumbnail. */
    suspend fun carryOver(oldPath: String, newPath: String) {
        history.renamePosition(oldPath, newPath)
        durations.rename(oldPath, newPath)
        thumbnails.rename(oldPath, newPath)
    }
}
