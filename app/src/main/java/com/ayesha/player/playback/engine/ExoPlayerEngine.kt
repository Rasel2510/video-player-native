package com.ayesha.player.playback.engine

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.ayesha.player.data.model.MediaTrack
import com.ayesha.player.data.model.TrackSnapshot
import com.ayesha.player.data.model.VideoSize
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Media3/ExoPlayer engine: ExoPlayerPlugin.ExoInstance from the Flutter build with the
 * channels removed. Logic carried over as is: decoder fallback, the "intent" signal,
 * keyframe seeks for scrubbing, the 300 ms position ticker, g<group>:t<track> ids, and the
 * undecodable-video detection (immediate when no decoder exists, 2.5 s watchdog otherwise).
 *
 * New: audio focus and "becoming noisy" handling (pause on calls and headphone unplug), and
 * a real 100-200 % volume boost via LoudnessEnhancer (the Flutter ExoPlayer build clamped it).
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(context: Context) : PlayerEngine, Player.Listener {

    private val main = Handler(Looper.getMainLooper())

    // Decoder fallback: if the preferred (hardware) decoder fails to initialise, e.g. 10-bit
    // HEVC on an 8-bit-only decoder, retry on another one instead of erroring out.
    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<EngineEvent> = _events
    override val isSoftware = false
    override val currentPositionMs: Long get() = player.currentPosition

    private var hasVideoTrack = false
    private var firstFrameRendered = false
    private val videoWatchdog = Runnable {
        if (hasVideoTrack && !firstFrameRendered) emit(EngineEvent.VideoUnsupported)
    }

    /** External subtitles accumulate, so a re-prepare keeps the ones already loaded. */
    private val externalSubs = ArrayList<MediaItem.SubtitleConfiguration>()
    private var currentUri: String? = null
    private var lastIntent = false
    private var released = false

    private var enhancer: LoudnessEnhancer? = null
    private var boostPercent = 100f

    // ExoPlayer doesn't push position continuously: poll ~3x/s while playing.
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (player.isPlaying) emit(EngineEvent.Position(player.currentPosition))
            main.postDelayed(this, 300)
        }
    }

    init {
        player.addListener(this)
        main.postDelayed(ticker, 300)
    }

    override fun setSurface(surface: Surface?) {
        if (surface == null) player.clearVideoSurface() else player.setVideoSurface(surface)
    }

    // ExoPlayer's own check: a no-op unless [surface] is the current output.
    override fun detachSurface(surface: Surface) {
        if (!released) player.clearVideoSurface(surface)
    }

    override fun open(path: String, startMs: Long, play: Boolean) {
        currentUri = path
        val item = MediaItem.Builder()
            .setUri(toUri(path))
            .setSubtitleConfigurations(externalSubs)
            .build()
        firstFrameRendered = false
        hasVideoTrack = false
        main.removeCallbacks(videoWatchdog)
        player.setMediaItem(item, startMs)
        player.playWhenReady = play
        player.prepare()
    }

    override fun play() {
        playFromEndIfNeeded()
        player.play()
    }

    override fun pause() = player.pause()

    override fun playOrPause() {
        // Toggle on intent (playWhenReady), so a tap during a seek's re-buffer matches the button.
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            playFromEndIfNeeded()
            player.play()
        }
    }

    override fun seek(ms: Long, fast: Boolean) {
        player.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.DEFAULT)
        player.seekTo(ms.coerceAtLeast(0))
        // Report right away: the ticker only polls while playing, so a paused seek would
        // otherwise leave the seek bar (and A-B points) on a stale position.
        emit(EngineEvent.Position(player.currentPosition))
    }

    override fun setRate(rate: Float) {
        player.playbackParameters = PlaybackParameters(rate)
    }

    override fun setVolume(percent: Float) {
        val p = percent.coerceIn(0f, 200f)
        player.volume = (p / 100f).coerceAtMost(1f)
        boostPercent = p
        applyBoost()
    }

    /** Gain above 100 %: +20·log10(p/100) dB, i.e. +6 dB at 200 %. */
    private fun applyBoost() {
        try {
            if (boostPercent <= 100f) {
                enhancer?.enabled = false
                return
            }
            val session = player.audioSessionId
            if (session == C.AUDIO_SESSION_ID_UNSET) return
            val e = enhancer ?: LoudnessEnhancer(session).also { enhancer = it }
            val gainMb = (2000 * log10(boostPercent / 100f)).roundToInt()
            e.setTargetGain(gainMb)
            e.enabled = true
        } catch (_: Exception) {
            // Some devices refuse audio effects; volume then simply caps at 100 %.
        }
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        enhancer?.release()
        enhancer = null
        applyBoost()
    }

    override fun setRepeatMode(mode: Int) {
        player.repeatMode = when (mode) {
            1 -> Player.REPEAT_MODE_ONE
            2 -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun selectAudioTrack(id: String?) = selectOrDisable(id, C.TRACK_TYPE_AUDIO)
    override fun selectSubtitleTrack(id: String?) = selectOrDisable(id, C.TRACK_TYPE_TEXT)

    private fun selectOrDisable(id: String?, type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, id == null)
            .build()
        if (id != null) selectTrack(id, type)
    }

    override fun addExternalSubtitle(uri: String, nameHint: String?): String? {
        if (uri.isEmpty()) return null
        val name = (nameHint ?: uri).lowercase()
        val mime = when {
            name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        externalSubs += MediaItem.SubtitleConfiguration.Builder(toUri(uri))
            .setMimeType(mime)
            .setLabel(nameHint)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        // Re-prepare at the current position so the new subtitle is picked up.
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        currentUri?.let { open(it, pos, wasPlaying) }
        return "ext:${externalSubs.size - 1}"
    }

    /** ExoPlayer has no subtitle delay; accepted as a no-op, as in the Flutter build. */
    override fun setSubtitleDelay(seconds: Float) = Unit

    private fun playFromEndIfNeeded() {
        // play() at STATE_ENDED is a no-op: rewind so play after the end replays.
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
    }

    private fun selectTrack(id: String, type: Int) {
        val parts = id.removePrefix("g").split(":t")
        val gi = parts.getOrNull(0)?.toIntOrNull() ?: return
        val ti = parts.getOrNull(1)?.toIntOrNull() ?: return
        val group = player.currentTracks.groups.filter { it.type == type }.getOrNull(gi) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ti))
            .build()
    }

    // ── Player.Listener ──────────────────────────────────────────────────────

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        emit(EngineEvent.Playing(isPlaying))
        emit(EngineEvent.Position(player.currentPosition))
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = reportIntent()

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY && player.duration > 0) emit(EngineEvent.Duration(player.duration))
        if (state == Player.STATE_ENDED) emit(EngineEvent.Completed)
        reportIntent()
    }

    private fun reportIntent() {
        val intent = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE
        if (intent != lastIntent) {
            lastIntent = intent
            emit(EngineEvent.Intent(intent))
        }
    }

    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
        // Rendering into a SurfaceView: MediaCodec applies the rotation itself and the size
        // is already the display size, so no extra rotation is needed (unlike Flutter's texture).
        if (size.width > 0 && size.height > 0) {
            val w = (size.width * size.pixelWidthHeightRatio).roundToInt()
            emit(EngineEvent.Size(VideoSize(w, size.height, 0)))
        }
    }

    override fun onRenderedFirstFrame() {
        firstFrameRendered = true
        main.removeCallbacks(videoWatchdog)
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        emit(EngineEvent.Rate(playbackParameters.speed))
    }

    override fun onPlayerError(error: PlaybackException) {
        emit(EngineEvent.Error(error.message ?: "Playback error"))
    }

    override fun onCues(cueGroup: CueGroup) {
        emit(EngineEvent.Cues(cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")))
    }

    override fun onTracksChanged(tracks: Tracks) {
        emit(EngineEvent.Tracks(snapshot(tracks)))
        hasVideoTrack = tracks.containsType(C.TRACK_TYPE_VIDEO)
        // No decoder at all for this video track: fall back now.
        if (hasVideoTrack && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
            emit(EngineEvent.VideoUnsupported)
            return
        }
        // A decoder claims support but may never produce a frame: arm the watchdog.
        if (hasVideoTrack && !firstFrameRendered) {
            main.removeCallbacks(videoWatchdog)
            main.postDelayed(videoWatchdog, 2500)
        }
    }

    private fun snapshot(tracks: Tracks): TrackSnapshot {
        val audio = ArrayList<MediaTrack>()
        val text = ArrayList<MediaTrack>()
        var activeAudio: String? = null
        var activeText: String? = null
        var ag = 0
        var tg = 0
        for (group in tracks.groups) {
            val isAudio = group.type == C.TRACK_TYPE_AUDIO
            val isText = group.type == C.TRACK_TYPE_TEXT
            if (!isAudio && !isText) continue
            val gi = if (isAudio) ag++ else tg++
            for (ti in 0 until group.length) {
                val f = group.getTrackFormat(ti)
                val id = "g$gi:t$ti"
                val t = MediaTrack(id, f.label, f.language)
                if (isAudio) {
                    audio += t
                    if (group.isTrackSelected(ti)) activeAudio = id
                } else {
                    text += t
                    if (group.isTrackSelected(ti)) activeText = id
                }
            }
        }
        return TrackSnapshot(audio, text, activeAudio, activeText)
    }

    private fun emit(e: EngineEvent) {
        if (!released) _events.tryEmit(e)
    }

    /**
     * Back to a clean state so the same instance can play the next video. Building an ExoPlayer
     * takes 50-100 ms of main-thread time on a budget phone; reusing one keeps that cost out of
     * the player's open animation. Events emitted during the reset have no collector and are dropped.
     */
    fun reset() {
        if (released) return
        main.removeCallbacks(videoWatchdog)
        runCatching {
            player.stop()
            player.clearMediaItems()
            player.clearVideoSurface()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            player.playbackParameters = PlaybackParameters.DEFAULT
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.volume = 1f
            player.setSeekParameters(SeekParameters.DEFAULT)
            enhancer?.enabled = false
        }
        boostPercent = 100f
        externalSubs.clear()
        currentUri = null
        lastIntent = false
        hasVideoTrack = false
        firstFrameRendered = false
    }

    override fun release() {
        if (released) return
        released = true
        main.removeCallbacks(ticker)
        main.removeCallbacks(videoWatchdog)
        runCatching { enhancer?.release() }
        runCatching { player.removeListener(this) }
        runCatching { player.release() }
    }

    private fun toUri(path: String): Uri = if (path.startsWith("/")) Uri.parse("file://$path") else Uri.parse(path)
}
