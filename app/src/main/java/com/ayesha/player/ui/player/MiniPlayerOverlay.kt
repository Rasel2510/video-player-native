package com.ayesha.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.theme.PlayerControlsStyle
import com.ayesha.player.core.util.PathUtil
import com.ayesha.player.data.prefs.AppearancePreferences
import com.ayesha.player.playback.PlayerController
import com.ayesha.player.playback.PlayerUiState
import com.ayesha.player.ui.common.pick
import com.ayesha.player.ui.common.GlassSurface
import com.ayesha.player.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    val controller: PlayerController,
    appearance: AppearancePreferences,
) : ViewModel() {
    val controlsStyle = appearance.settings.map { it?.controlsStyle ?: PlayerControlsStyle.Tint }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerControlsStyle.Tint)
}

private const val BASE_W = 200f
private const val BASE_H = 112f
private const val MARGIN = 12f
private const val MIN_SCALE = 0.85f
private const val MAX_SCALE = 2f
private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/**
 * mini_player_overlay.dart: a floating window over every screen while minimized. Drag it
 * anywhere (it snaps to the nearest corner, or the flung direction), pinch to resize
 * (0.85x-2x), tap for controls (auto-hide after 3 s). Dimensions in dp.
 */
@Composable
fun MiniPlayerOverlay(onExpand: (Route.Player) -> Unit, vm: MiniPlayerViewModel = hiltViewModel()) {
    val st = vm.controller.state.collectAsStateWithLifecycle()
    // Composed above every screen: while hidden it must not recompose on each position tick.
    val visible = st.pick { it.isMiniPlayerActive && it.isActive && it.currentPath != null }
    if (visible) MiniPlayerWindow(st, vm, onExpand)
}

@Composable
private fun MiniPlayerWindow(st: State<PlayerUiState>, vm: MiniPlayerViewModel, onExpand: (Route.Player) -> Unit) {
    val controller = vm.controller
    val engine by controller.engine.collectAsStateWithLifecycle()
    val style by vm.controlsStyle.collectAsStateWithLifecycle()
    val playing = st.pick { it.isPlaying }
    val videoW = st.pick { it.videoWidth }
    val videoH = st.pick { it.videoHeight }
    val initialized = st.pick { it.isInitialized }
    val ended = st.pick { it.hasEnded }
    val hasPrevious = st.pick { it.hasPrevious }
    val hasNext = st.pick { it.hasNext }
    val interval = st.pick { it.seekInterval }
    val intendsToPlay = st.pick { it.intendsToPlay }

    val view = LocalView.current
    LaunchedEffect(playing) { view.keepScreenOn = playing }
    DisposableEffect(Unit) { onDispose { view.keepScreenOn = false } }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var corner by rememberSaveable { mutableIntStateOf(3) } // 0 TL, 1 TR, 2 BL, 3 BR
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var controlsVisible by remember { mutableStateOf(false) }
    var hideTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hideTick) {
        if (controlsVisible) {
            delay(3000)
            controlsVisible = false
        }
    }
    fun showControls() {
        controlsVisible = true
        hideTick++
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val screenW = maxWidth.value
        val screenH = maxHeight.value
        // Flutter keeps max(bottom inset, 12) + 12 clear at the bottom: with no navigation bar
        // inset that's 24 dp, not 12.
        val bottomInset = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp().value }
        val bottomMargin = MARGIN + max(0f, MARGIN - bottomInset)
        val dynamicMax = min(MAX_SCALE, max(MIN_SCALE, min((screenW - 2 * MARGIN) / BASE_W, (screenH - MARGIN - bottomMargin) / BASE_H)))
        val eff = min(scale, dynamicMax)
        val w = BASE_W * eff
        val h = BASE_H * eff

        // All positions are the window's top-left in dp, for an explicit scale (the gesture
        // below outlives recompositions, so nothing may capture the current scale implicitly).
        fun cornerPos(c: Int, sc: Float): Offset {
            val ww = BASE_W * sc
            val hh = BASE_H * sc
            return when (c) {
                0 -> Offset(MARGIN, MARGIN)
                1 -> Offset(screenW - ww - MARGIN, MARGIN)
                2 -> Offset(MARGIN, screenH - hh - bottomMargin)
                else -> Offset(screenW - ww - MARGIN, screenH - hh - bottomMargin)
            }
        }
        fun clampPos(p: Offset, sc: Float): Offset = Offset(
            p.x.coerceIn(MARGIN, max(MARGIN, screenW - BASE_W * sc - MARGIN)),
            p.y.coerceIn(MARGIN, max(MARGIN, screenH - BASE_H * sc - bottomMargin)),
        )

        // At rest the window sits exactly at its corner (so rotation or a resize just moves it
        // there); while dragged, at the corner plus the drag; while snapping, where the snap is.
        var dragging by remember { mutableStateOf(false) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        var snapping by remember { mutableStateOf(false) }
        val snap = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        fun currentPos(sc: Float): Offset = when {
            dragging -> clampPos(cornerPos(corner, sc) + dragOffset, sc)
            snapping -> snap.value
            else -> cornerPos(corner, sc)
        }

        /** Same rule as the Dart build: a fling (> 400 dp/s) picks by direction, else the nearest corner. */
        fun pickCorner(current: Offset, vx: Float, vy: Float, sc: Float): Int {
            val speed = kotlin.math.sqrt(vx * vx + vy * vy)
            if (speed > 400f) {
                val isLeft = corner == 0 || corner == 2
                val isTop = corner == 0 || corner == 1
                return when {
                    abs(vy) > abs(vx) * 1.5f -> if (isLeft) (if (vy >= 0) 2 else 0) else (if (vy >= 0) 3 else 1)
                    abs(vx) > abs(vy) * 1.5f -> if (isTop) (if (vx >= 0) 1 else 0) else (if (vx >= 0) 3 else 2)
                    else -> if (vx >= 0) (if (vy >= 0) 3 else 1) else (if (vy >= 0) 2 else 0)
                }
            }
            val half = Offset(BASE_W * sc / 2, BASE_H * sc / 2)
            val center = current + half
            return (0..3).minBy { (cornerPos(it, sc) + half - center).getDistance() }
        }

        Box(
            Modifier
                // Read while placing: dragging moves the window without recomposing it.
                .offset {
                    val p = currentPos(eff)
                    IntOffset((p.x * density.density).roundToInt(), (p.y * density.density).roundToInt())
                }
                .size(Dp(w), Dp(h))
                .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = Color(0xDD000000))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (controlsVisible) controlsVisible = false else showControls()
                    })
                }
                .pointerInput(screenW, screenH, bottomMargin) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        // Finger travel in screen pixels. The window moves with the finger, so
                        // positions local to it can't give the fling speed or the slop distance.
                        var travel = Offset.Zero
                        tracker.addPosition(down.uptimeMillis, travel)
                        var started = false
                        var baseScale = min(scale, dynamicMax)
                        var zoomAcc = 1f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val pan = ev.calculatePan()
                            val zoom = if (pressed.size >= 2) ev.calculateZoom() else 1f
                            travel += pan
                            tracker.addPosition(pressed.first().uptimeMillis, travel)
                            if (!started) {
                                // Total distance, not this event's: slow drags move too.
                                if (pressed.size < 2 && travel.getDistance() <= viewConfiguration.touchSlop) continue
                                started = true
                                // Grabbed mid-snap: carry on from where it is on screen.
                                val sc = min(scale, dynamicMax)
                                dragOffset = currentPos(sc) - cornerPos(corner, sc)
                                if (snapping) {
                                    snapping = false
                                    scope.launch { snap.stop() }
                                }
                                dragging = true
                                baseScale = sc
                            }
                            if (zoom != 1f) {
                                zoomAcc *= zoom
                                scale = (baseScale * zoomAcc).coerceIn(MIN_SCALE, dynamicMax)
                            }
                            // Relative to the corner, so a pinch grows the window out of its
                            // corner instead of off the screen; kept inside the screen.
                            val sc = min(scale, dynamicMax)
                            val base = cornerPos(corner, sc)
                            dragOffset = clampPos(base + dragOffset + pan / density.density, sc) - base
                            pressed.forEach { it.consume() }
                        }
                        if (!started) return@awaitEachGesture
                        val sc = min(scale, dynamicMax)
                        val current = clampPos(cornerPos(corner, sc) + dragOffset, sc)
                        val v = tracker.calculateVelocity()
                        val target = pickCorner(current, v.x / density.density, v.y / density.density, sc)
                        // Undispatched: the snap starts at the release point on this very frame.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            snap.snapTo(current)
                            snapping = true
                            dragging = false
                            corner = target
                            snap.animateTo(cornerPos(target, sc), tween(300, easing = EaseOutCubic))
                            snapping = false
                        }
                    }
                },
        ) {
            VideoSurface(engine, videoW, videoH, com.ayesha.player.playback.FitMode.Contain, 1f, density = density.density)
            if (!initialized) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = Color(0x8AFFFFFF), strokeWidth = 2.dp)
            }
            val iconScale = min(1.3f, eff)
            AnimatedVisibility(controlsVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                GlassSurface(style, Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
                    MiniButton(Icons.Rounded.Close, 18f * iconScale, Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp)) {
                        controller.dispose()
                    }
                    MiniButton(Icons.Rounded.Fullscreen, 24f * iconScale, Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp)) {
                        val s = st.value
                        val path = s.currentPath ?: return@MiniButton
                        val folder = if (s.folderVideos.isNotEmpty()) PathUtil.parent(path) else null
                        onExpand(Route.Player(path, s.title, null, folder))
                    }
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        if (ended) {
                            // Played to the end: previous / replay / next instead of seek / play / seek.
                            MiniButton(Icons.Rounded.SkipPrevious, 26f * iconScale, Modifier.alpha(if (hasPrevious) 1f else 0.4f)) {
                                if (hasPrevious) controller.playPrevious()
                                showControls()
                            }
                            MiniButton(Icons.Rounded.Replay, 32f * iconScale) { controller.replay(); showControls() }
                            MiniButton(Icons.Rounded.SkipNext, 26f * iconScale, Modifier.alpha(if (hasNext) 1f else 0.4f)) {
                                if (hasNext) controller.playNext()
                                showControls()
                            }
                        } else {
                            Box(Modifier.clip(CircleShape).clickable { controller.seekRelative(-interval, revealControls = false); showControls() }.padding(12.dp)) {
                                SeekIconContent(false, interval, big = false)
                            }
                            MiniButton(if (intendsToPlay) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 32f * iconScale) {
                                controller.togglePlay()
                                showControls()
                            }
                            Box(Modifier.clip(CircleShape).clickable { controller.seekRelative(interval, revealControls = false); showControls() }.padding(12.dp)) {
                                SeekIconContent(true, interval, big = false)
                            }
                        }
                    }
                }
            }
            if (!controlsVisible) {
                LinearProgressIndicator(
                    // Read while drawing: position ticks redraw the bar without recomposing.
                    progress = { st.value.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = AppTheme.colors.accent,
                    trackColor = Color(0x3DFFFFFF),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun MiniButton(icon: androidx.compose.ui.graphics.vector.ImageVector, sizeDp: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(CircleShape).clickable(onClick = onClick).padding(12.dp)) {
        Icon(icon, null, Modifier.size(Dp(sizeDp)), tint = Color.White)
    }
}
