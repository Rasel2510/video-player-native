package com.ayesha.player.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayesha.player.MainActivity
import com.ayesha.player.core.theme.PlayerControlsStyle
import com.ayesha.player.playback.PlayerController
import com.ayesha.player.playback.PlayerUiState
import com.ayesha.player.playback.RotationMode
import com.ayesha.player.playback.engine.PlayerEngine
import com.ayesha.player.ui.common.pick
import com.ayesha.player.ui.navigation.Route
import kotlinx.coroutines.launch

private enum class PlayerSheet { None, Speed, Volume, Audio, Subtitles, Sleep }

/** The overlay's open animation (360 ms) plus a frame. */
private const val OPEN_SETTLE_MS = 400L

/**
 * player_screen.dart.
 *
 * The state is passed down as a [State] and each part picks the fields it draws, so the
 * position ticking several times a second recomposes the time, the slider and nothing else.
 */
@Composable
fun PlayerScreen(args: Route.Player, onClose: (PlayerExit) -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val controller = vm.controller
    val st = vm.state.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val subtitleStyle by vm.subtitleStyle.collectAsStateWithLifecycle()
    val controlsStyle by vm.controlsStyle.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(PlayerSheet.None) }
    val surface = remember { VideoSurfaceHandle() }
    val latestOnClose by rememberUpdatedState(onClose)
    // Plain flag: only guards against a second back press, nothing draws from it.
    val exiting = remember { booleanArrayOf(false) }

    LaunchedEffect(Unit) { vm.start(args) }

    /**
     * Copies the current frame first: the overlay flies that picture back into the video's card
     * ([toCard]) or fades it, since the video surface itself can't be animated.
     */
    fun exitPlayer(toCard: Boolean = true) {
        if (exiting[0]) return
        exiting[0] = true
        // Answers the press at once (the controls fade) while the frame is being copied.
        controller.hideControls()
        // Closing for good: only pause and save now. The teardown runs when this screen is
        // disposed, after the close animation (onDispose below), so none of it lands mid-flight.
        if (toCard) controller.pauseForExit()
        scope.launch {
            surface.freeze()
            val path = controller.state.value.currentPath ?: args.path
            val info = PlayerExit(surface.frame, surface.windowBounds(), path, toCard)
            // Mini player and audio mode keep playing: leaving is cheap, and must happen now.
            if (!toCard) controller.leaveScreen()
            latestOnClose(info)
        }
    }
    BackHandler { exitPlayer() }
    DisposableEffect(Unit) {
        // A no-op when the mini player or audio mode already left; otherwise the full teardown.
        onDispose { controller.leaveScreen() }
    }

    PlayerWindowEffects(st, activity)
    CopyWarmUp(st, surface)

    // ── Pickers ──────────────────────────────────────────────────────────────
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        controller.loadExternalSubtitle(uri.toString(), name)
    }

    val actions = remember(controller) {
        object : PlayerActions {
            override fun back() = exitPlayer()
            override fun togglePlay() = controller.togglePlay()
            override fun cycleFit() = controller.cycleFitMode()
            override fun showSpeed() { sheet = PlayerSheet.Speed }
            override fun showVolume() { sheet = PlayerSheet.Volume }
            override fun showAudio() { sheet = PlayerSheet.Audio }
            override fun showSubtitles() { sheet = PlayerSheet.Subtitles }
            override fun seekBack() = controller.seekRelative(-controller.state.value.seekInterval)
            override fun seekForward() = controller.seekRelative(controller.state.value.seekInterval)
            override fun cycleRotation() = controller.cycleRotationMode()
            override fun seekStart(v: Float) = controller.beginSeek(v)
            override fun seekUpdate(v: Float) = controller.updateSeek(v)
            override fun seekEnd(v: Float) = controller.endSeek(v)
            override fun next() = controller.playNext()
            override fun previous() = controller.playPrevious()
            override fun lock() = controller.toggleLock()
            override fun cycleLoop() = controller.cycleLoopMode()
            override fun audioMode() {
                // Keep playing as audio after leaving; controlled from the notification.
                controller.enableAudioMode()
                exitPlayer(toCard = false)
            }
            override fun sleepTimer() { sheet = PlayerSheet.Sleep }
            override fun pip() {
                val s = controller.state.value
                (activity as? MainActivity)?.enterPip(s.videoWidth, s.videoHeight)
            }
            override fun cycleAb() = controller.cycleAbRepeat()
            override fun miniPlayer() {
                controller.minimize()
                exitPlayer(toCard = false)
            }
        }
    }

    val pip = st.pick { it.isPipMode }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Same slot in every mode, so entering PiP doesn't recreate the surface (black flash).
        VideoLayer(st, engine, surface, density)
        // PiP: only the raw video.
        if (pip) return@Box
        val locked = st.pick { it.isLocked }
        val seekInterval = st.pick { it.seekInterval }
        val holdFastForward = st.pick { it.holdFastForward }
        GestureLayer(controller, locked, seekInterval, holdFastForward) {
            Box(Modifier.fillMaxSize()) {
                StatusLayer(st, vm, args.path, onBack = { exitPlayer() })
                SubtitleLayer(st, subtitleStyle, vm::setSubtitleOffset)
                SwipeHud(st.pick { it.swipeGesture }, st.pick { it.swipeValue })
                AutoPlayCountdown(
                    st.pick { it.autoPlayCountdown },
                    st.pick { it.nextVideo?.name },
                    onPlayNow = controller::playNext,
                    onCancel = controller::cancelAutoPlay,
                )
                ZoomIndicator(st.pick { it.zoomScale }, controller::resetZoom)
                PlayerControls(
                    st.pick { it.isInitialized && !it.hasError && it.controlsVisible && !it.isLocked },
                    st,
                    controlsStyle,
                    actions,
                )
            }
        }
        LockLayer(locked, controlsStyle, onUnlock = controller::toggleLock)
    }

    PlayerSheets(sheet, st, controller, vm, subtitleStyle, onLoadSubtitle = { subtitlePicker.launch(arrayOf("*/*")) }) {
        sheet = PlayerSheet.None
    }
}

@Composable
private fun VideoLayer(st: State<PlayerUiState>, engine: PlayerEngine?, handle: VideoSurfaceHandle, density: Float) {
    VideoSurface(
        engine,
        st.pick { it.videoWidth },
        st.pick { it.videoHeight },
        st.pick { it.fitMode },
        st.pick { if (it.isPipMode) 1f else it.zoomScale },
        density = density,
        handle = handle,
        // PiP shows only the raw video, as for text subtitles.
        bitmapCues = st.pick { if (it.subtitlesEnabled && !it.isPipMode) it.bitmapCues else emptyList() },
    )
}

/** The error screen, or the loading poster until the first frame. */
@Composable
private fun StatusLayer(st: State<PlayerUiState>, vm: PlayerViewModel, openedPath: String, onBack: () -> Unit) {
    val hasError = st.pick { it.hasError }
    val initialized = st.pick { it.isInitialized }
    val path = st.pick { it.currentPath }
    // Seeded synchronously from the in-memory cache, so the Hero has its target on the first frame.
    val poster by produceState(vm.peekThumbnail(openedPath), path) {
        value = path?.let { vm.thumbnail(it) }
    }
    if (hasError) {
        ErrorState(st.pick { it.errorMessage }, onRetry = vm::retry, onBack = onBack)
    }
    LoadingPoster(!initialized && !hasError, poster)
}

@Composable
private fun SubtitleLayer(st: State<PlayerUiState>, style: SubtitleStyle, onDragEnd: (Float, Float) -> Unit) {
    val enabled = st.pick { it.subtitlesEnabled }
    val cue = st.pick { it.currentCue }
    if (enabled) SubtitleOverlay(cue, style, onDragEnd)
}

/** Brightness, keep-screen-on, orientation and status-bar icons; recomposes only on those. */
@Composable
private fun PlayerWindowEffects(st: State<PlayerUiState>, activity: Activity?) {
    val view = LocalView.current
    val brightness = st.pick { it.brightnessOverride }
    val playing = st.pick { it.isPlaying }
    val rotation = st.pick { it.rotationMode }

    LaunchedEffect(brightness) {
        activity?.window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    LaunchedEffect(playing) { view.keepScreenOn = playing }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            view.keepScreenOn = false
        }
    }
    // The player is always black: light status-bar icons here, the theme's again as soon as it
    // starts closing (the list shows through during the close animation).
    val closing = PlayerOverlay.exit != null
    DisposableEffect(activity, closing) {
        if (closing) return@DisposableEffect onDispose {}
        val w = activity?.window
        val bars = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val wasLight = bars?.isAppearanceLightStatusBars ?: false
        val wasLightNav = bars?.isAppearanceLightNavigationBars ?: false
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            bars?.isAppearanceLightStatusBars = wasLight
            bars?.isAppearanceLightNavigationBars = wasLightNav
        }
    }
    val opened = remember { booleanArrayOf(false) }
    LaunchedEffect(rotation) {
        val a = activity ?: return@LaunchedEffect
        // Changing the requested orientation re-lays out the whole window (~45 ms here): not
        // in the middle of the open animation.
        if (!opened[0]) {
            opened[0] = true
            kotlinx.coroutines.delay(OPEN_SETTLE_MS)
        }
        a.requestedOrientation = when (rotation) {
            RotationMode.Auto -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            RotationMode.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            RotationMode.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        // Locked orientations go immersive (sticky); auto stays edge-to-edge, as in the Flutter build.
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (rotation == RotationMode.Auto) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** The open sheet only; reads the state just for the fields that sheet shows. */
@Composable
private fun PlayerSheets(
    sheet: PlayerSheet,
    st: State<PlayerUiState>,
    controller: PlayerController,
    vm: PlayerViewModel,
    subtitleStyle: SubtitleStyle,
    onLoadSubtitle: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (sheet) {
        PlayerSheet.None -> Unit
        PlayerSheet.Speed -> SpeedSheet(
            st.pick { it.playbackSpeed }, st.pick { it.seekInterval },
            controller::setSpeed, controller::setSeekInterval, onDismiss,
        )
        PlayerSheet.Volume -> VolumeSheet(st.pick { it.volume }, controller::setVolume, onDismiss)
        PlayerSheet.Audio -> AudioTrackSheet(
            st.pick { it.audioTracks }, st.pick { it.selectedAudioTrack }, st.pick { it.audioEnabled },
            onSelect = controller::setAudioTrack, onDisable = controller::disableAudio, onDismiss = onDismiss,
        )
        PlayerSheet.Subtitles -> SubtitleSheet(
            tracks = st.pick { it.subtitleTracks },
            selected = st.pick { it.selectedSubtitleTrack },
            enabled = st.pick { it.subtitlesShowing },
            delay = st.pick { it.subtitleDelay },
            style = subtitleStyle,
            vm = vm,
            onSelect = controller::setSubtitleTrack,
            onToggle = controller::toggleSubtitles,
            onAdjustDelay = controller::adjustSubtitleDelay,
            onResetDelay = { controller.setSubtitleDelay(0f) },
            onLoadExternal = onLoadSubtitle,
            onDismiss = onDismiss,
        )
        PlayerSheet.Sleep -> SleepTimerSheet(
            st.pick { it.sleepTimerEndsAt }, st.pick { it.sleepTimerEndOfVideo },
            onSet = { controller.setSleepTimer(durationMs = it) },
            onEndOfVideo = { controller.setSleepTimer(endOfVideo = true) },
            onCancel = controller::cancelSleepTimer,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Copies a tiny frame once, about a second after playback starts. The first copy of a session
 * compiles the GPU conversion and sets up its buffers (~100 ms on the render thread here); done
 * now, while nothing in the app animates (the video plays on its own surface, unaffected), the
 * copy made when closing is the quick one and the close animation starts sooner.
 */
@Composable
private fun CopyWarmUp(st: State<PlayerUiState>, surface: VideoSurfaceHandle) {
    val initialized = st.pick { it.isInitialized }
    LaunchedEffect(initialized) {
        if (!initialized) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        surface.warmUp()
    }
}
