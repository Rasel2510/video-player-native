package com.ayesha.player.data.model

import androidx.compose.runtime.Immutable

/**
 * A selectable audio or subtitle track (player_engine.dart). [id] is the engine's opaque
 * handle: for ExoPlayer the group/track index encoded as a string.
 */
@Immutable
data class MediaTrack(
    val id: String,
    val title: String? = null,
    val language: String? = null,
)

/** Available plus active tracks, emitted whenever the track set or selection changes. */
@Immutable
data class TrackSnapshot(
    val audio: List<MediaTrack> = emptyList(),
    val subtitle: List<MediaTrack> = emptyList(),
    val activeAudioId: String? = null,
    /** Null means subtitles are off. */
    val activeSubtitleId: String? = null,
) {
    companion object {
        val Empty = TrackSnapshot()
    }
}

/**
 * Decoded video size. [rotation] is the clockwise degrees (0/90/180/270) the display still
 * has to apply; ExoPlayer rendering into a raw Surface doesn't rotate for metadata itself.
 */
@Immutable
data class VideoSize(val width: Int, val height: Int, val rotation: Int = 0) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** Aspect ratio as displayed, after applying [rotation]. */
    val displayAspect: Float
        get() = when {
            isEmpty -> 16f / 9f
            rotation % 180 != 0 -> height.toFloat() / width
            else -> width.toFloat() / height
        }

    companion object {
        val Zero = VideoSize(0, 0)
    }
}
