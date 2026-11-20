package com.ayesha.player.playback.engine

import android.graphics.Bitmap
import android.view.Surface
import androidx.compose.runtime.Immutable
import com.ayesha.player.data.model.TrackSnapshot
import com.ayesha.player.data.model.VideoSize
import kotlinx.coroutines.flow.SharedFlow

/** Everything an engine reports (player_engine.dart's streams, as one event type). */
sealed interface EngineEvent {
    data class Playing(val playing: Boolean) : EngineEvent

    /**
     * The user's playback intent (playWhenReady, excluding ended/idle). Unlike [Playing] it
     * doesn't flip false during a seek's re-buffer, so it drives the play/pause button.
     */
    data class Intent(val intendsToPlay: Boolean) : EngineEvent
    data class Position(val ms: Long) : EngineEvent
    data class Duration(val ms: Long) : EngineEvent
    data class Rate(val rate: Float) : EngineEvent
    data class Tracks(val snapshot: TrackSnapshot) : EngineEvent
    data class Size(val size: VideoSize) : EngineEvent

    /**
     * The active subtitle text ('' = none), drawn by the app's own styled overlay, plus any
     * image subtitles (PGS / VobSub / DVB), drawn over the video where the file places them.
     */
    data class Cues(val text: String, val bitmaps: List<BitmapCue> = emptyList()) : EngineEvent
    data object Completed : EngineEvent
    data class Error(val message: String) : EngineEvent

    /** The file has a video track this engine can't decode: switch to the software engine. */
    data object VideoUnsupported : EngineEvent
}

/**
 * Engine-agnostic player (player_engine.dart). Implemented by [ExoPlayerEngine] (hardware,
 * Media3) and the libmpv software fallback. All calls must be made on the main thread.
 */
interface PlayerEngine {
    val events: SharedFlow<EngineEvent>

    /** True for the software (libmpv) fallback. */
    val isSoftware: Boolean

    val currentPositionMs: Long

    /** Render target; null detaches (e.g. while the view is being recreated). */
    fun setSurface(surface: Surface?)

    /**
     * Detaches [surface] only if it's still the render target. Two views can hold the engine
     * at once (the closing player screen and the mini player that replaces it); the one going
     * away must not clear the other's surface.
     */
    fun detachSurface(surface: Surface)

    /** The surface's pixel size changed (libmpv needs it; ExoPlayer tracks it itself). */
    fun setSurfaceSize(width: Int, height: Int) = Unit

    fun open(path: String, startMs: Long = 0, play: Boolean = true)
    fun play()
    fun pause()
    fun playOrPause()

    /** [fast]: snap to the nearest keyframe (scrubbing), instead of an exact seek. */
    fun seek(ms: Long, fast: Boolean = false)
    fun setRate(rate: Float)

    /** 0..200 %; above 100 % the engine amplifies. */
    fun setVolume(percent: Float)

    /** 0 = off, 1 = repeat one, 2 = repeat all. */
    fun setRepeatMode(mode: Int)

    /** Null disables the audio track entirely. */
    fun selectAudioTrack(id: String?)

    /** Null disables subtitles. */
    fun selectSubtitleTrack(id: String?)

    /** Loads an external subtitle file or content URI; returns its new track id. */
    fun addExternalSubtitle(uri: String, nameHint: String?): String?

    /** Subtitle timing offset in seconds (+ later). Engines that can't shift cues ignore it. */
    fun setSubtitleDelay(seconds: Float)

    /** A JPEG of the current frame (software engine only, for files with no thumbnail). */
    suspend fun screenshot(): ByteArray? = null

    fun release()
}

/**
 * An image subtitle, placed the way Media3's Cue describes it: [position] / [line] are fractions
 * of the video's width / height where the [positionAnchor] / [lineAnchor] edge of the image sits,
 * [size] its width as a fraction of the video width, [height] as a fraction of the video height
 * (null = keep the image's aspect ratio). Identity equality: each cue is a new image.
 */
@Immutable
class BitmapCue(
    val bitmap: Bitmap,
    val position: Float,
    val positionAnchor: Anchor,
    val line: Float,
    val lineAnchor: Anchor,
    val size: Float,
    val height: Float?,
) {
    enum class Anchor { Start, Middle, End }
}

/** Creates the software fallback engine. Phase 7 binds the libmpv one; until then there is none. */
fun interface SoftwareEngineFactory {
    fun create(): PlayerEngine?
}
