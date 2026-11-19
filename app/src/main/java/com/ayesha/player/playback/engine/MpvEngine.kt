package com.ayesha.player.playback.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.ayesha.player.data.model.MediaTrack
import com.ayesha.player.data.model.TrackSnapshot
import com.ayesha.player.data.model.VideoSize
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/**
 * Software engine on libmpv + FFmpeg: the fallback for files the device's MediaCodec can't
 * decode (media_kit_engine.dart). Decoding is forced to software (hwdec=no), since hardware
 * decoding is exactly what failed. mpv's own subtitle rendering is hidden; cues are forwarded
 * as text to the app's styled overlay, as with ExoPlayer.
 *
 * mpv calls the observer on its own thread; everything is re-posted to the main thread.
 */
class MpvEngine(private val context: Context) : PlayerEngine, MPVLib.EventObserver {

    private val main = Handler(Looper.getMainLooper())
    // Null when libmpv can't start here; SoftwareEngineFactory turns the exception into "no fallback".
    private val mpv: MPVLib = MPVLib.create(context) ?: throw IllegalStateException("libmpv unavailable")

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<EngineEvent> = _events
    override val isSoftware = true

    @Volatile private var positionMs = 0L
    override val currentPositionMs: Long get() = positionMs

    private var paused = true
    private var eof = false
    private var fileLoaded = false
    private var lastPlaying: Boolean? = null
    private var lastIntent: Boolean? = null
    private var lastPositionEmit = 0L
    private var dw = 0L
    private var dh = 0L
    private var released = false

    init {
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("hwdec", "no")
        mpv.setOptionString("ao", "audiotrack,opensles")
        // Keep the last frame at the end, so "eof-reached" reports completion and play replays.
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("input-default-bindings", "no")
        mpv.setOptionString("save-position-on-quit", "no")
        mpv.setOptionString("volume-max", "200")
        // Subtitles are drawn by the app (styled overlay); mpv only supplies the text.
        mpv.setOptionString("sub-visibility", "no")
        mpv.init()
        mpv.addObserver(this)
        observe("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observe("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observe("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observe("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observe("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observe("dwidth", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observe("dheight", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observe("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        observe("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observe("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        observe("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    private fun observe(name: String, format: Int) = mpv.observeProperty(name, format)

    // ── Surface ──────────────────────────────────────────────────────────────

    private var attached: Surface? = null

    override fun detachSurface(surface: Surface) {
        if (attached === surface) setSurface(null)
    }

    override fun setSurface(surface: Surface?) {
        if (released) return
        attached = surface
        if (surface == null) {
            runCatching {
                mpv.setPropertyString("vo", "null")
                mpv.setPropertyString("force-window", "no")
                mpv.detachSurface()
            }
        } else {
            mpv.attachSurface(surface)
            mpv.setOptionString("force-window", "yes")
            mpv.setPropertyString("vo", "gpu")
        }
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        if (!released && width > 0 && height > 0) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    override fun open(path: String, startMs: Long, play: Boolean) {
        fileLoaded = false
        eof = false
        mpv.setPropertyBoolean("pause", !play)
        mpv.setPropertyString("start", if (startMs > 0) String.format(java.util.Locale.ROOT, "%.3f", startMs / 1000.0) else "none")
        mpv.command(arrayOf("loadfile", toMpvPath(path), "replace"))
    }

    override fun play() {
        if (eof) seek(0)
        mpv.setPropertyBoolean("pause", false)
    }

    override fun pause() = mpv.setPropertyBoolean("pause", true)

    override fun playOrPause() {
        if (!paused && !eof) pause() else play()
    }

    override fun seek(ms: Long, fast: Boolean) {
        val sec = String.format(java.util.Locale.ROOT, "%.3f", ms.coerceAtLeast(0) / 1000.0)
        mpv.command(arrayOf("seek", sec, if (fast) "absolute+keyframes" else "absolute+exact"))
        positionMs = ms.coerceAtLeast(0)
        emit(EngineEvent.Position(positionMs))
    }

    override fun setRate(rate: Float) = mpv.setPropertyDouble("speed", rate.toDouble())

    /** mpv's volume goes past 100 natively (volume-max = 200). */
    override fun setVolume(percent: Float) = mpv.setPropertyDouble("volume", percent.coerceIn(0f, 200f).toDouble())

    /** A single file is the whole "playlist", so repeat-one and repeat-all both loop it (as with ExoPlayer). */
    override fun setRepeatMode(mode: Int) = mpv.setPropertyString("loop-file", if (mode == 0) "no" else "inf")

    override fun selectAudioTrack(id: String?) = mpv.setPropertyString("aid", id ?: "no")
    override fun selectSubtitleTrack(id: String?) = mpv.setPropertyString("sid", id ?: "no")

    override fun addExternalSubtitle(uri: String, nameHint: String?): String? {
        if (uri.isEmpty()) return null
        mpv.command(arrayOf("sub-add", toMpvPath(uri), "select", nameHint ?: "External"))
        return "ext"
    }

    override fun setSubtitleDelay(seconds: Float) = mpv.setPropertyDouble("sub-delay", seconds.toDouble())

    /** The decoded frame as JPEG (for files no other extractor could thumbnail). */
    override suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        if (released || !fileLoaded) return@withContext null
        val out = File(context.cacheDir, "mpv_shot_${SystemClock.elapsedRealtime()}.jpg")
        try {
            mpv.command(arrayOf("screenshot-to-file", out.path, "video"))
            out.takeIf { it.exists() && it.length() > 0 }?.readBytes()
        } catch (_: Exception) {
            null
        } finally {
            out.delete()
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { mpv.removeObserver(this) }
        runCatching { mpv.detachSurface() }
        runCatching { mpv.destroy() }
    }

    /** content:// can't be opened by mpv directly: hand it a file descriptor it closes itself. */
    private fun toMpvPath(path: String): String {
        if (!path.startsWith("content://")) return path
        return try {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r") ?: return path
            "fdclose://${pfd.detachFd()}"
        } catch (_: Exception) {
            path
        }
    }

    // ── mpv events (mpv thread -> main) ──────────────────────────────────────

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        main.post {
            when (property) {
                "dwidth" -> { dw = value; reportSize() }
                "dheight" -> { dh = value; reportSize() }
                "track-list/count" -> reportTracks()
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        if (property == "time-pos") {
            positionMs = (value * 1000).roundToLong()
            // mpv reports per frame; ~4x/s is plenty (ExoPlayer's ticker is 3x/s).
            val now = SystemClock.elapsedRealtime()
            if (now - lastPositionEmit >= 250) {
                lastPositionEmit = now
                main.post { emit(EngineEvent.Position(positionMs)) }
            }
            return
        }
        main.post {
            when (property) {
                "duration" -> if (value > 0) emit(EngineEvent.Duration((value * 1000).roundToLong()))
                "speed" -> emit(EngineEvent.Rate(value.toFloat()))
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        main.post {
            when (property) {
                "pause" -> { paused = value; reportPlaying() }
                "eof-reached" -> {
                    val was = eof
                    eof = value
                    reportPlaying()
                    if (value && !was) emit(EngineEvent.Completed)
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        main.post {
            when (property) {
                "sub-text" -> emit(EngineEvent.Cues(value))
                "aid", "sid" -> reportTracks()
            }
        }
    }

    override fun event(eventId: Int) {
        main.post {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    fileLoaded = true
                    // "start" is sticky across loadfile: reset it so the next file starts at 0.
                    runCatching { mpv.setPropertyString("start", "none") }
                    reportPlaying()
                    reportTracks()
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    // Ended before it ever loaded: the software engine can't open it either.
                    if (!fileLoaded && !released) emit(EngineEvent.Error("This file can't be opened"))
                }
            }
        }
    }

    private fun reportPlaying() {
        val playing = fileLoaded && !paused && !eof
        if (playing != lastPlaying) {
            lastPlaying = playing
            emit(EngineEvent.Playing(playing))
            emit(EngineEvent.Position(positionMs))
        }
        val intent = !paused && !eof
        if (intent != lastIntent) {
            lastIntent = intent
            emit(EngineEvent.Intent(intent))
        }
    }

    private fun reportSize() {
        if (dw > 0 && dh > 0) emit(EngineEvent.Size(VideoSize(dw.toInt(), dh.toInt(), 0)))
    }

    private fun reportTracks() {
        if (released) return
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        val audio = ArrayList<MediaTrack>()
        val subs = ArrayList<MediaTrack>()
        var activeAudio: String? = null
        var activeSub: String? = null
        for (i in 0 until count) {
            val type = mpv.getPropertyString("track-list/$i/type") ?: continue
            val id = mpv.getPropertyInt("track-list/$i/id")?.toString() ?: continue
            val t = MediaTrack(id, mpv.getPropertyString("track-list/$i/title"), mpv.getPropertyString("track-list/$i/lang"))
            val selected = mpv.getPropertyBoolean("track-list/$i/selected") == true
            when (type) {
                "audio" -> {
                    audio += t
                    if (selected) activeAudio = id
                }
                "sub" -> {
                    subs += t
                    if (selected) activeSub = id
                }
            }
        }
        emit(EngineEvent.Tracks(TrackSnapshot(audio, subs, activeAudio, activeSub)))
    }

    private fun emit(e: EngineEvent) {
        if (!released) _events.tryEmit(e)
    }
}
