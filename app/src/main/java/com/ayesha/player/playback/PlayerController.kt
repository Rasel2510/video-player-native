package com.ayesha.player.playback

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.ayesha.player.data.device.VolumeController
import com.ayesha.player.data.library.PlaybackHistory
import com.ayesha.player.data.media.DurationRepository
import com.ayesha.player.data.media.ThumbnailRepository
import com.ayesha.player.data.model.MediaTrack
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.prefs.PlaybackPreferences
import com.ayesha.player.playback.engine.EngineEvent
import com.ayesha.player.playback.engine.ExoPlayerEngine
import com.ayesha.player.playback.engine.PlayerEngine
import com.ayesha.player.playback.engine.SoftwareEngineFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of PlayerNotifier (player_provider.dart). App-scoped, so playback survives leaving the
 * player screen (mini player, audio mode).
 *
 * Window-level effects (brightness, orientation, keep-screen-on, immersive mode) are left to
 * the player screen, which applies them from [state]. Everything runs on the main thread.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val history: PlaybackHistory,
    private val durations: DurationRepository,
    private val thumbnails: ThumbnailRepository,
    private val prefs: PlaybackPreferences,
    private val volumeController: VolumeController,
    private val softwareEngines: SoftwareEngineFactory,
    private val connector: PlaybackConnector,
) {
    private val scope = MainScope()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private inline fun set(block: PlayerUiState.() -> PlayerUiState) = _state.update(block)
    private val s get() = _state.value

    private val _engine = MutableStateFlow<PlayerEngine?>(null)

    /** The current engine; the video surface attaches to it. */
    val engine: StateFlow<PlayerEngine?> = _engine.asStateFlow()

    private var eventsJob: Job? = null
    private var hideJob: Job? = null
    private var lockIconJob: Job? = null
    private var hudJob: Job? = null
    private var saveJob: Job? = null
    private var autoPlayJob: Job? = null
    private var sleepJob: Job? = null
    private var readyFallbackJob: Job? = null
    private var volumeJob: Job? = null

    /** A ready ExoPlayer, built while the app was idle or kept from the last video. */
    private var spareEngine: ExoPlayerEngine? = null

    private var preHoldSpeed = 1f
    private var hasStartedPlaying = false
    private var usingFallback = false
    private var isDisposing = false
    private var leftScreen = false
    private var appVolumeChangeAt = 0L
    private var lastPeriodicSave = 0L

    /**
     * Where the current file should resume. Kept so the software fallback lands right even
     * when ExoPlayer failed before reporting any position.
     */
    private var resumeTargetMs: Long? = null

    private val _artPath = MutableStateFlow<String?>(null)

    /** Resolved thumbnail for the lock screen / notification art. */
    val artPath: StateFlow<String?> = _artPath.asStateFlow()

    val audioMode: Boolean get() = s.audioMode

    // ── Init ─────────────────────────────────────────────────────────────────

    fun init(path: String, name: String, resumeMs: Long?, playlist: List<VideoFile>, index: Int) {
        // Coming back from the mini player to the same file: just restore it.
        if (s.isMiniPlayerActive && s.currentPath == path && _engine.value != null) {
            restoreFromMiniPlayer()
            return
        }
        isDisposing = false
        leftScreen = false
        hasStartedPlaying = false
        usingFallback = false
        disposeInternal()
        _artPath.value = null

        _state.value = PlayerUiState(
            folderVideos = playlist,
            currentIndex = index,
            fileName = name,
            currentPath = path,
            isActive = true,
            seekInterval = prefs.settings.value?.seekIntervalSeconds ?: 10,
        )

        val engine = obtainExoEngine()
        _engine.value = engine
        listen(engine) { onReady() }

        // Open and play right away; preferences are applied as they arrive.
        val start = resumeMs?.takeIf { it > 0 }
        resumeTargetMs = start
        engine.open(path, start ?: 0, play = true)

        scope.launch {
            val p = prefs.current()
            val fit = FitMode.entries.getOrElse(p.fitModeIndex) { FitMode.Contain }
            val loop = LoopMode.entries.getOrElse(p.loopModeIndex) { LoopMode.None }
            val deviceVol = volumeController.get()
            val brightness = p.brightness ?: systemBrightness()
            set {
                copy(
                    volume = deviceVol * 100f,
                    fitMode = fit,
                    playbackSpeed = p.speed,
                    brightness = brightness,
                    // Only take over the screen brightness when the user set one before.
                    brightnessOverride = p.brightness,
                    loopMode = loop,
                    seekInterval = p.seekIntervalSeconds,
                )
            }
            if (_engine.value === engine) {
                engine.setVolume(100f)
                engine.setRate(p.speed)
                engine.setRepeatMode(loop.repeatCode)
            }
        }
        watchDeviceVolume()
        // Notification, lock screen and headset buttons, and the foreground service. Starting
        // the service costs ~40 ms of main-thread time: after the open animation, not during it.
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(SERVICE_CONNECT_DELAY_MS)
            if (!isDisposing) connector.connect()
        }
    }

    private var connectJob: Job? = null

    /**
     * Builds the ExoPlayer ahead of time (call when the app is idle), so opening the first video
     * doesn't spend 50-100 ms of main-thread time constructing one during the open animation.
     */
    fun prewarm() {
        if (spareEngine == null && _engine.value == null) spareEngine = ExoPlayerEngine(context)
    }

    private fun obtainExoEngine(): ExoPlayerEngine = spareEngine?.also { spareEngine = null } ?: ExoPlayerEngine(context)

    /** Keeps a finished ExoPlayer (reset) for the next video; anything else is released. */
    private fun recycle(engine: PlayerEngine?) {
        if (engine == null) return
        if (engine is ExoPlayerEngine && spareEngine == null) {
            engine.reset()
            spareEngine = engine
        } else {
            runCatching { engine.release() }
        }
    }

    private fun onReady() {
        set { copy(isInitialized = true) }
        startHideTimer()
        resolveArt()
    }

    private fun watchDeviceVolume() {
        volumeJob?.cancel()
        volumeJob = scope.launch {
            volumeController.changes().collect { v ->
                if (isDisposing) return@collect
                // Ignore the echo of our own change.
                if (SystemClock.elapsedRealtime() - appVolumeChangeAt < 600) return@collect
                if (s.volume <= 100f) set { copy(volume = v * 100f) }
            }
        }
    }

    private fun systemBrightness(): Float = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (_: Exception) {
        0.5f
    }

    // ── Software fallback ────────────────────────────────────────────────────

    /**
     * Swaps ExoPlayer for the software (libmpv) engine and reopens the file where it was.
     * Triggered when ExoPlayer errors or can't decode the video track.
     */
    private fun fallbackToSoftware() {
        if (usingFallback || isDisposing) return
        val path = s.currentPath ?: return
        val software = softwareEngines.create()
        if (software == null) {
            set { copy(hasError = true, errorMessage = "This video's format isn't supported on this device", isInitialized = true) }
            return
        }
        usingFallback = true
        hasStartedPlaying = false
        val resumeAt = if (s.positionMs > 0) s.positionMs else (resumeTargetMs ?: 0)

        eventsJob?.cancel()
        val old = _engine.value
        _engine.value = null
        recycle(old)

        set {
            copy(
                isInitialized = false,
                hasError = false,
                errorMessage = null,
                usingSoftwareDecoder = true,
                currentCue = "",
                bitmapCues = emptyList(),
            )
        }
        _engine.value = software
        listen(software) {
            set { copy(isInitialized = true) }
            startHideTimer()
            // Grab the poster from this engine's frame before resolving the art: otherwise the
            // thumbnail service would open a second software decoder on the playing file.
            scope.launch {
                captureFallbackArt(software, path)
                if (!isDisposing) resolveArt()
            }
        }
        software.setVolume(100f)
        software.setRate(s.playbackSpeed)
        software.setRepeatMode(s.loopMode.repeatCode)
        // Carry the subtitle state over: the new engine would otherwise start with its defaults.
        software.setSubtitleDelay(s.subtitleDelay)
        if (!s.subtitlesEnabled) software.selectSubtitleTrack(null)
        software.setPictureInPicture(s.isPipMode)
        software.open(path, resumeAt, play = true)
    }

    private suspend fun captureFallbackArt(engine: PlayerEngine, path: String) {
        if (thumbnails.peek(path) != null) return
        for (waitMs in longArrayOf(1500, 2500, 4000)) {
            delay(waitMs)
            if (isDisposing || _engine.value !== engine) return
            val shot = withTimeoutOrNull(3000) { engine.screenshot() }
            if (shot != null && shot.isNotEmpty()) {
                thumbnails.store(path, shot)
                return
            }
        }
    }

    // ── Engine events ────────────────────────────────────────────────────────

    private fun listen(engine: PlayerEngine, onReadyOnce: () -> Unit) {
        eventsJob?.cancel()
        var frameReady = false
        fun markReady() {
            if (frameReady) return
            frameReady = true
            readyFallbackJob?.cancel()
            onReadyOnce()
        }
        // UNDISPATCHED: subscribe right now, before the caller opens the file. A normal launch only
        // subscribes on the next main-loop pass, and the engine's first events (intent "playing",
        // duration, size) were lost in that gap: the play/pause icon then stayed wrong until tapped.
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { e ->
                if (isDisposing && e !is EngineEvent.Position) return@collect
                when (e) {
                    is EngineEvent.Playing -> {
                        // Actually playing implies the intent to play: keeps the icon right even if an
                        // intent event was ever missed.
                        set { copy(isPlaying = e.playing, intendsToPlay = if (e.playing) true else intendsToPlay) }
                        if (e.playing) {
                            markReady()
                            hasStartedPlaying = true
                        } else if (hasStartedPlaying && !isDisposing) {
                            savePosition()
                        }
                    }
                    is EngineEvent.Intent -> set { copy(intendsToPlay = e.intendsToPlay) }
                    is EngineEvent.Position -> onPosition(e.ms)
                    is EngineEvent.Duration -> if (e.ms > 0) {
                        set { copy(durationMs = e.ms) }
                        s.currentPath?.let { p -> scope.launch { durations.save(p, e.ms.milliseconds) } }
                    }
                    // Float rates come back as 1.2000000476...: snap to 2 decimals.
                    is EngineEvent.Rate -> set { copy(playbackSpeed = (e.rate * 100).roundToInt() / 100f) }
                    is EngineEvent.Tracks -> set {
                        copy(
                            audioTracks = e.snapshot.audio,
                            // Don't clobber "audio disabled" (the engine then reports no active audio).
                            selectedAudioTrack = if (audioEnabled) e.snapshot.audio.find { it.id == e.snapshot.activeAudioId } else null,
                            subtitleTracks = e.snapshot.subtitle,
                            // While off, the engine reports no active track: keep the user's pick,
                            // so turning subtitles back on returns to it instead of the first track.
                            selectedSubtitleTrack = if (subtitlesEnabled) {
                                e.snapshot.subtitle.find { it.id == e.snapshot.activeSubtitleId }
                            } else {
                                selectedSubtitleTrack?.takeIf { t -> e.snapshot.subtitle.any { it.id == t.id } }
                            },
                        )
                    }
                    is EngineEvent.Size -> if (!e.size.isEmpty) {
                        set { copy(videoWidth = e.size.width, videoHeight = e.size.height) }
                        markReady()
                    }
                    is EngineEvent.Cues -> set {
                        copy(
                            currentCue = if (subtitlesEnabled) e.text else "",
                            bitmapCues = if (subtitlesEnabled) e.bitmaps else emptyList(),
                        )
                    }
                    EngineEvent.Completed -> onCompleted()
                    is EngineEvent.Error -> if (e.message.isNotEmpty()) {
                        // ExoPlayer can't decode it: retry once in software before showing an error.
                        if (!usingFallback && !engine.isSoftware) {
                            fallbackToSoftware()
                        } else {
                            set { copy(hasError = true, errorMessage = e.message, isInitialized = true) }
                        }
                    }
                    // ExoPlayer often plays the audio with no picture instead of erroring.
                    EngineEvent.VideoUnsupported -> if (!usingFallback && !engine.isSoftware) fallbackToSoftware()
                }
            }
        }
        readyFallbackJob?.cancel()
        readyFallbackJob = scope.launch {
            delay(4000)
            if (!isDisposing) markReady()
        }
    }

    private fun onPosition(ms: Long) {
        set { copy(positionMs = ms) }
        val a = s.abRepeatStartMs
        val b = s.abRepeatEndMs
        if (a != null && b != null && ms >= b) _engine.value?.seek(a)

        // Save 3 s after positions stop (pause, leave), as in the Dart build...
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(3000)
            savePosition()
        }
        // ...plus every 15 s while playing, so a crash or kill can't lose the whole session.
        val now = SystemClock.elapsedRealtime()
        if (s.isPlaying && now - lastPeriodicSave > 15_000) {
            lastPeriodicSave = now
            savePosition()
        }
    }

    private fun onCompleted() {
        if (isDisposing) return
        val a = s.abRepeatStartMs
        // A-B repeat wins: the clip reaching its end inside a loop goes back to A.
        if (a != null && s.abRepeatEndMs != null) {
            _engine.value?.seek(a)
            _engine.value?.play()
            return
        }
        set { copy(hasEnded = true) }
        if (s.sleepTimerEndOfVideo) {
            _engine.value?.pause()
            set { copy(sleepTimerEndOfVideo = false) }
            return
        }
        if (s.hasNext) startAutoPlayCountdown()
    }

    private fun savePosition() {
        val path = s.currentPath ?: return
        val pos = s.positionMs
        val dur = s.durationMs
        scope.launch {
            history.savePosition(
                path,
                pos.milliseconds,
                dur.milliseconds,
            )
        }
    }

    private fun resolveArt() {
        val path = s.currentPath ?: return
        if (_artPath.value != null) return
        scope.launch {
            val f = thumbnails.getThumbnail(path)
            if (!isDisposing && f != null && s.currentPath == path) _artPath.value = f.path
        }
    }

    // ── Auto-play next ───────────────────────────────────────────────────────

    private fun startAutoPlayCountdown() {
        autoPlayJob?.cancel()
        autoPlayJob = scope.launch {
            for (n in 5 downTo 1) {
                set { copy(autoPlayCountdown = n) }
                delay(1000)
            }
            set { copy(autoPlayCountdown = null) }
            playNext()
        }
    }

    fun cancelAutoPlay() {
        autoPlayJob?.cancel()
        set { copy(autoPlayCountdown = null) }
    }

    // ── Next / previous ──────────────────────────────────────────────────────

    /** The session (notification / lock screen) picked a playlist entry. */
    fun playIndex(index: Int) {
        if (index == s.currentIndex) return
        val v = s.folderVideos.getOrNull(index) ?: return
        switchVideo(v.path, index)
    }

    /** Live position straight from the engine (the state is only updated ~3x/s). */
    fun currentPositionMs(): Long = _engine.value?.currentPositionMs ?: s.positionMs

    fun playNext() {
        if (!s.hasNext) return
        val i = s.currentIndex + 1
        switchVideo(s.folderVideos[i].path, i)
    }

    fun playPrevious() {
        if (!s.hasPrevious) return
        val i = s.currentIndex - 1
        switchVideo(s.folderVideos[i].path, i)
    }

    private fun switchVideo(path: String, index: Int) {
        val engine = _engine.value ?: return
        autoPlayJob?.cancel()
        savePosition()
        _artPath.value = null
        hasStartedPlaying = false
        // Next/previous start at 0: a fallback for this file must not reuse the old resume point.
        resumeTargetMs = null
        val video = s.folderVideos.getOrNull(index)
        set {
            copy(
                currentIndex = index,
                currentPath = path,
                isInitialized = false,
                positionMs = 0,
                durationMs = 0,
                isSeeking = false,
                autoPlayCountdown = null,
                hasError = false,
                errorMessage = null,
                zoomScale = 1f,
                subtitleDelay = 0f,
                currentCue = "",
                bitmapCues = emptyList(),
                // Track ids are positional ("g0:t1"): the pick would land on another language here.
                selectedSubtitleTrack = null,
                abRepeatStartMs = null,
                abRepeatEndMs = null,
                hasEnded = false,
            )
        }
        if (video != null) scope.launch { history.addRecent(video) }
        listen(engine) {
            set { copy(isInitialized = true) }
            startHideTimer()
            if (engine.isSoftware) {
                scope.launch {
                    captureFallbackArt(engine, path)
                    if (!isDisposing) resolveArt()
                }
            } else {
                resolveArt()
            }
        }
        engine.setVolume(if (s.volume > 100f) s.volume else 100f)
        engine.setRate(s.playbackSpeed)
        engine.setRepeatMode(s.loopMode.repeatCode)
        // The state's delay was just reset: the engine's (mpv keeps it across files) must follow.
        engine.setSubtitleDelay(0f)
        engine.open(path, 0, play = true)
    }

    // ── Lock ─────────────────────────────────────────────────────────────────

    fun toggleLock() {
        val locked = !s.isLocked
        set { copy(isLocked = locked, controlsVisible = !locked, lockIconVisible = locked) }
        if (locked) {
            hideJob?.cancel()
            startLockIconTimer()
        } else {
            lockIconJob?.cancel()
            showControls()
        }
    }

    private fun startLockIconTimer() {
        lockIconJob?.cancel()
        lockIconJob = scope.launch {
            delay(2000)
            if (s.isLocked && !isDisposing) set { copy(lockIconVisible = false) }
        }
    }

    fun showLockIcon() {
        if (!s.isLocked) return
        set { copy(lockIconVisible = true) }
        startLockIconTimer()
    }

    // ── Zoom / fit / rotation / loop ─────────────────────────────────────────

    fun setZoomScale(scale: Float) = set { copy(zoomScale = scale.coerceIn(0.5f, 4f)) }
    fun resetZoom() = set { copy(zoomScale = 1f) }

    fun cycleFitMode() {
        val next = s.fitMode.next
        set { copy(fitMode = next, zoomScale = 1f) }
        prefs.setFitModeIndex(next.ordinal)
        showControls()
    }

    /** The screen applies the orientation lock and immersive mode for [RotationMode]. */
    fun cycleRotationMode() {
        set { copy(rotationMode = rotationMode.next) }
        showControls()
    }

    fun cycleLoopMode() {
        val next = s.loopMode.next
        set { copy(loopMode = next) }
        prefs.setLoopModeIndex(next.ordinal)
        applyEngineRepeatMode()
        showControls()
    }

    /** While an A-B loop is fully set, it owns looping: the engine's repeat is forced off. */
    private fun applyEngineRepeatMode() {
        val abActive = s.abRepeatStartMs != null && s.abRepeatEndMs != null
        _engine.value?.setRepeatMode(if (abActive) 0 else s.loopMode.repeatCode)
    }

    // ── Tracks / subtitles ───────────────────────────────────────────────────

    fun setSubtitleTrack(track: MediaTrack) {
        _engine.value?.selectSubtitleTrack(track.id)
        set { copy(selectedSubtitleTrack = track, subtitlesEnabled = true) }
        showControls()
    }

    fun toggleSubtitles() {
        // "On" means a track is actually showing: a file whose tracks aren't flagged default
        // opens with none, and the first tap should then turn one on, not "off".
        if (s.subtitlesShowing) {
            _engine.value?.selectSubtitleTrack(null)
            set { copy(subtitlesEnabled = false, currentCue = "", bitmapCues = emptyList()) }
        } else {
            // With no track at all, stay off: "ON" with nothing selected would be misleading.
            val target = s.selectedSubtitleTrack ?: s.subtitleTracks.firstOrNull()
            if (target != null) {
                _engine.value?.selectSubtitleTrack(target.id)
                set { copy(selectedSubtitleTrack = target, subtitlesEnabled = true) }
            }
        }
        showControls()
    }

    /** External .srt/.vtt/.ass; the engine re-prepares and the new track shows up in the list. */
    fun loadExternalSubtitle(uri: String, name: String?) {
        val engine = _engine.value ?: return
        engine.addExternalSubtitle(uri, name)
        set { copy(subtitlesEnabled = true) }
        showControls()
    }

    fun setAudioTrack(track: MediaTrack) {
        _engine.value?.selectAudioTrack(track.id)
        set { copy(selectedAudioTrack = track, audioEnabled = true) }
        showControls()
    }

    /** Silences the stream itself (not the same as volume 0). */
    fun disableAudio() {
        _engine.value?.selectAudioTrack(null)
        set { copy(selectedAudioTrack = null, audioEnabled = false) }
        showControls()
    }

    fun setSubtitleDelay(seconds: Float) {
        val v = seconds.coerceIn(-60f, 60f)
        _engine.value?.setSubtitleDelay(v)
        set { copy(subtitleDelay = v) }
        showControls()
    }

    fun adjustSubtitleDelay(delta: Float) = setSubtitleDelay(s.subtitleDelay + delta)

    // ── Swipe brightness / volume ────────────────────────────────────────────

    fun startSwipe(x: Float, width: Float): SwipeGesture {
        hudJob?.cancel()
        val g = if (x < width / 2) SwipeGesture.Brightness else SwipeGesture.Volume
        set { copy(swipeGesture = g, swipeValue = if (g == SwipeGesture.Brightness) brightness else volume / 200f) }
        return g
    }

    fun updateSwipe(dy: Float, height: Float) {
        if (s.swipeGesture == SwipeGesture.None) return
        hudJob?.cancel()
        val delta = -(dy / (height * 0.6f))
        if (s.swipeGesture == SwipeGesture.Brightness) {
            val b = (s.brightness + delta).coerceIn(0f, 1f)
            set { copy(brightness = b, brightnessOverride = b, swipeValue = b) }
        } else {
            val v = (s.volume + delta * 100f).coerceIn(0f, 200f)
            applyVolume(v)
            set { copy(volume = v, swipeValue = v / 200f) }
        }
    }

    fun endSwipe() {
        hudJob?.cancel()
        hudJob = scope.launch {
            delay(1500)
            set { copy(swipeGesture = SwipeGesture.None) }
        }
        if (s.swipeGesture == SwipeGesture.Brightness) prefs.setBrightness(s.brightness)
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 200f)
        applyVolume(v)
        set { copy(volume = v) }
    }

    /** Up to 100 % moves the device volume; above it the device is at max and the engine boosts. */
    private fun applyVolume(v: Float) {
        appVolumeChangeAt = SystemClock.elapsedRealtime()
        if (v <= 100f) {
            volumeController.set(v / 100f)
            _engine.value?.setVolume(100f)
        } else {
            volumeController.set(1f)
            _engine.value?.setVolume(v)
        }
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    private fun startHideTimer() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(3000)
            if (s.isPlaying && !s.isLocked) set { copy(controlsVisible = false) }
        }
    }

    fun showControls() {
        if (s.isLocked) return
        set { copy(controlsVisible = true) }
        startHideTimer()
    }

    fun hideControls() {
        set { copy(controlsVisible = false) }
        hideJob?.cancel()
    }

    /**
     * The player screen is closing: silent and saved right away. The rest of the teardown
     * ([leaveScreen], via the screen's disposal) waits until the close animation has finished:
     * detaching the video surface blocks the main thread for ~70 ms while ExoPlayer's playback
     * thread lets go of it, which used to land on the animation's first frame.
     */
    fun pauseForExit() {
        if (isDisposing) return
        hideJob?.cancel()
        runCatching { _engine.value?.pause() }
        savePosition()
    }

    fun togglePlay() {
        if (isDisposing) return
        val engine = _engine.value ?: return
        if (s.autoPlayCountdown != null) cancelAutoPlay()
        if (s.hasEnded) set { copy(hasEnded = false) }
        engine.playOrPause()
        showControls()
    }

    fun play() = _engine.value?.play()
    fun pause() = _engine.value?.pause()

    /** Mini player's "Replay" after the end. */
    fun replay() {
        if (isDisposing) return
        val engine = _engine.value ?: return
        if (s.autoPlayCountdown != null) cancelAutoPlay()
        set { copy(hasEnded = false) }
        engine.seek(0)
        engine.play()
        showControls()
    }

    fun seekRelative(seconds: Int, revealControls: Boolean = true) {
        val engine = _engine.value ?: return
        if (s.autoPlayCountdown != null) cancelAutoPlay()
        if (s.hasEnded) set { copy(hasEnded = false) }
        val target = (s.positionMs + seconds * 1000L).coerceIn(0, s.durationMs.coerceAtLeast(0))
        engine.seek(target, fast = true)
        if (revealControls) showControls()
    }

    fun seekTo(ms: Long) = _engine.value?.seek(ms, fast = true)

    fun beginSeek(value: Float) {
        if (s.autoPlayCountdown != null) cancelAutoPlay()
        set { copy(isSeeking = true, seekValue = value) }
    }

    fun updateSeek(value: Float) = set { copy(seekValue = value) }

    fun endSeek(value: Float) {
        val engine = _engine.value ?: return
        if (s.autoPlayCountdown != null) cancelAutoPlay()
        if (s.hasEnded) set { copy(hasEnded = false) }
        // Scrubbing: keyframe-snap so the release lands instantly.
        engine.seek((value * s.durationMs).roundToInt().toLong(), fast = true)
        set { copy(isSeeking = false) }
        showControls()
    }

    fun setSpeed(speed: Float) {
        val clean = (speed * 100).roundToInt() / 100f
        _engine.value?.setRate(clean)
        set { copy(playbackSpeed = clean) }
        prefs.setSpeed(clean)
        showControls()
    }

    fun setSeekInterval(seconds: Int) {
        set { copy(seekInterval = seconds) }
        prefs.setSeekInterval(seconds)
    }

    // ── Sleep timer ──────────────────────────────────────────────────────────

    fun setSleepTimer(durationMs: Long? = null, endOfVideo: Boolean = false) {
        sleepJob?.cancel()
        sleepJob = null
        when {
            endOfVideo -> set { copy(sleepTimerEndsAt = null, sleepTimerEndOfVideo = true) }
            durationMs != null -> {
                sleepJob = scope.launch {
                    delay(durationMs)
                    sleepJob = null
                    _engine.value?.pause()
                    set { copy(sleepTimerEndsAt = null, sleepTimerEndOfVideo = false) }
                }
                set { copy(sleepTimerEndsAt = System.currentTimeMillis() + durationMs, sleepTimerEndOfVideo = false) }
            }
            else -> set { copy(sleepTimerEndsAt = null, sleepTimerEndOfVideo = false) }
        }
        showControls()
    }

    fun cancelSleepTimer() = setSleepTimer()

    // ── Hold for 2x ──────────────────────────────────────────────────────────

    fun startHoldFastForward() {
        if (s.holdFastForward) return
        val engine = _engine.value ?: return
        preHoldSpeed = s.playbackSpeed
        engine.setRate(2f)
        set { copy(holdFastForward = true) }
    }

    fun endHoldFastForward() {
        if (!s.holdFastForward) return
        _engine.value?.setRate(preHoldSpeed)
        set { copy(holdFastForward = false) }
    }

    // ── A-B repeat ───────────────────────────────────────────────────────────

    fun cycleAbRepeat() {
        val a = s.abRepeatStartMs
        val pos = _engine.value?.currentPositionMs ?: s.positionMs
        when {
            a == null -> set { copy(abRepeatStartMs = pos) }
            s.abRepeatEndMs == null -> if (pos <= a) {
                set { copy(abRepeatStartMs = pos) }
            } else {
                set { copy(abRepeatEndMs = pos) }
                applyEngineRepeatMode()
            }
            else -> clearAbRepeat()
        }
        showControls()
    }

    fun clearAbRepeat() {
        set { copy(abRepeatStartMs = null, abRepeatEndMs = null) }
        applyEngineRepeatMode()
    }

    // ── PiP / mini player / audio mode / leaving ─────────────────────────────

    fun setPipMode(pip: Boolean) {
        _engine.value?.setPictureInPicture(pip)
        set { copy(isPipMode = pip) }
    }

    fun enableAudioMode() = set { copy(audioMode = true) }

    fun minimize() = set { copy(isMiniPlayerActive = true) }

    fun restoreFromMiniPlayer() {
        set { copy(isMiniPlayerActive = false) }
        leftScreen = false
    }

    /**
     * The player screen is going away. Audio mode and the mini player keep playing; otherwise
     * playback stops and the engine is released.
     */
    fun leaveScreen() {
        if (leftScreen) return
        leftScreen = true
        if (s.audioMode || s.isMiniPlayerActive) {
            hideJob?.cancel()
            lockIconJob?.cancel()
            hudJob?.cancel()
            saveJob?.cancel()
            savePosition()
            // The screen's brightness override no longer applies off the player screen.
            set { copy(brightnessOverride = null, isLocked = false) }
        } else {
            dispose()
        }
    }

    /** Stops playback completely (also used by the notification's close / mini player dismiss). */
    fun dispose() {
        if (isDisposing) return
        isDisposing = true
        autoPlayJob?.cancel()
        volumeJob?.cancel()
        hideJob?.cancel()
        lockIconJob?.cancel()
        hudJob?.cancel()
        sleepJob?.cancel()
        readyFallbackJob?.cancel()
        runCatching { _engine.value?.pause() }
        saveJob?.cancel()
        savePosition()
        eventsJob?.cancel()
        val old = _engine.value
        _engine.value = null
        // Defer the heavy native release so it doesn't compete with the pop transition.
        scope.launch(Dispatchers.Main) {
            delay(350)
            recycle(old)
        }
        _state.value = PlayerUiState()
        connectJob?.cancel()
        connector.disconnect()
    }

    private fun disposeInternal() {
        autoPlayJob?.cancel()
        lockIconJob?.cancel()
        sleepJob?.cancel()
        readyFallbackJob?.cancel()
        saveJob?.cancel()
        eventsJob?.cancel()
        recycle(_engine.value)
        _engine.value = null
    }
}

/** After the player's 360 ms open animation. */
private const val SERVICE_CONNECT_DELAY_MS = 600L
