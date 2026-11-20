package com.ayesha.player.playback

import androidx.compose.runtime.Immutable
import com.ayesha.player.data.model.MediaTrack
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.playback.engine.BitmapCue

/** Persisted by ordinal (player_fit_mode_v1): keep the order. */
enum class FitMode(val label: String) {
    Contain("FIT"), Cover("CROP"), Fill("FILL"), Natural("AUTO");

    val next: FitMode get() = entries[(ordinal + 1) % entries.size]
}

enum class RotationMode {
    Auto, Landscape, Portrait;

    val next: RotationMode
        get() = when (this) {
            Auto -> Landscape
            Landscape -> Portrait
            Portrait -> Auto
        }
}

enum class SwipeGesture { None, Brightness, Volume }

/** Persisted by ordinal (player_loop_mode_v1): keep the order. */
enum class LoopMode {
    None, LoopAll, LoopOne;

    val next: LoopMode get() = entries[(ordinal + 1) % entries.size]
    val isActive: Boolean get() = this != None

    /** Engine repeat code: 0 off, 1 one, 2 all. */
    val repeatCode: Int
        get() = when (this) {
            None -> 0
            LoopOne -> 1
            LoopAll -> 2
        }
}

/** Port of PlayerState (player_provider.dart). Times are milliseconds. */
@Immutable
data class PlayerUiState(
    val isInitialized: Boolean = false,
    val isPlaying: Boolean = false,
    /** playWhenReady: drives the play/pause button so it doesn't flicker during a seek's re-buffer. */
    val intendsToPlay: Boolean = false,
    val controlsVisible: Boolean = true,
    val isPipMode: Boolean = false,
    val rotationMode: RotationMode = RotationMode.Auto,
    val isSeeking: Boolean = false,
    val seekValue: Float = 0f,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0..200 %. */
    val volume: Float = 100f,
    val brightness: Float = 0.5f,
    /** Window brightness to apply while the player is visible; null = leave the system's. */
    val brightnessOverride: Float? = null,
    val playbackSpeed: Float = 1f,
    val fitMode: FitMode = FitMode.Contain,
    val audioTracks: List<MediaTrack> = emptyList(),
    val selectedAudioTrack: MediaTrack? = null,
    /** False when the user disabled the audio track ("Disable" option). */
    val audioEnabled: Boolean = true,
    val swipeGesture: SwipeGesture = SwipeGesture.None,
    val swipeValue: Float = 0f,
    val folderVideos: List<VideoFile> = emptyList(),
    val currentIndex: Int = -1,
    /** Title for a single file (no playlist). */
    val fileName: String = "",
    val currentPath: String? = null,
    val subtitleTracks: List<MediaTrack> = emptyList(),
    val selectedSubtitleTrack: MediaTrack? = null,
    val subtitlesEnabled: Boolean = true,
    val isLocked: Boolean = false,
    val lockIconVisible: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String? = null,
    val autoPlayCountdown: Int? = null,
    val zoomScale: Float = 1f,
    val loopMode: LoopMode = LoopMode.None,
    val currentCue: String = "",
    /** Image subtitles (PGS / VobSub / DVB) showing now, drawn over the video. */
    val bitmapCues: List<BitmapCue> = emptyList(),
    val videoWidth: Int = 16,
    val videoHeight: Int = 9,
    /** Wall-clock end of the sleep timer (epoch ms), or null. */
    val sleepTimerEndsAt: Long? = null,
    val sleepTimerEndOfVideo: Boolean = false,
    /** Seconds; + = subtitles later. */
    val subtitleDelay: Float = 0f,
    val holdFastForward: Boolean = false,
    val abRepeatStartMs: Long? = null,
    val abRepeatEndMs: Long? = null,
    val seekInterval: Int = 10,
    val isMiniPlayerActive: Boolean = false,
    /** Played to its natural end (mini player swaps to previous/replay/next). */
    val hasEnded: Boolean = false,
    /** Playing on the software (libmpv) engine. */
    val usingSoftwareDecoder: Boolean = false,
    /** Audio mode: playback continues after leaving the screen. */
    val audioMode: Boolean = false,
    /** True while a player exists (screen, mini player or audio mode). */
    val isActive: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs > 0) (if (isSeeking) seekValue else positionMs.toFloat() / durationMs) else 0f

    val hasPrevious: Boolean get() = currentIndex > 0
    val hasNext: Boolean get() = currentIndex >= 0 && currentIndex < folderVideos.size - 1

    /** Subtitles are on and a track is actually selected (what the sheet's ON/OFF shows). */
    val subtitlesShowing: Boolean get() = subtitlesEnabled && selectedSubtitleTrack != null
    val currentVideo: VideoFile? get() = folderVideos.getOrNull(currentIndex)
    val nextVideo: VideoFile? get() = if (hasNext) folderVideos[currentIndex + 1] else null
    val title: String get() = currentVideo?.name ?: fileName
}
