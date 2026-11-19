package com.ayesha.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.ayesha.player.playback.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * player_gesture_layer.dart. Two detectors on the same area:
 *  - taps: tap toggles controls, double-tap seeks (left/right half), long-press = 2x while held;
 *  - drags: after 15 dp, horizontal = scrub, vertical = brightness (left) / volume (right);
 *    two fingers = pinch zoom (with a 150 ms cooldown so the lifting finger can't start a swipe).
 * A committed drag consumes its events, which cancels the pending tap / long-press.
 */
@Composable
fun GestureLayer(controller: PlayerController, locked: Boolean, seekInterval: Int, holdFastForward: Boolean, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    var flashForward by remember { mutableStateOf<Boolean?>(null) }
    var holdActive by remember { mutableStateOf(false) }
    var pinchCooldown by remember { mutableStateOf(false) }
    val currentLocked by rememberUpdatedState(locked)
    val currentInterval by rememberUpdatedState(seekInterval)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val slopPx = with(density) { 15.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val s = controller.state.value
                            if (s.controlsVisible) controller.hideControls() else controller.showControls()
                        },
                        onDoubleTap = { pos ->
                            if (currentLocked) return@detectTapGestures
                            val left = pos.x < widthPx / 2
                            controller.seekRelative(if (left) -currentInterval else currentInterval, revealControls = false)
                            flashForward = !left
                            scope.launch {
                                flash.snapTo(0f)
                                flash.animateTo(1f, tween(750, easing = LinearEasing))
                                flashForward = null
                            }
                        },
                        onLongPress = {
                            if (currentLocked) return@detectTapGestures
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            holdActive = true
                            controller.startHoldFastForward()
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (holdActive) {
                                holdActive = false
                                controller.endHoldFastForward()
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentLocked || pinchCooldown) return@awaitEachGesture
                        val start = down.position
                        var pinching = false
                        var committed = false
                        var seekSwipe = false
                        var seekStartProgress = 0f
                        var baseZoom = 1f
                        var zoomAcc = 1f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2 || pinching) {
                                if (!pinching) {
                                    pinching = true
                                    baseZoom = controller.state.value.zoomScale
                                    zoomAcc = 1f
                                    // A second finger means pinch, not hold or swipe.
                                    if (holdActive) {
                                        holdActive = false
                                        controller.endHoldFastForward()
                                    }
                                    if (committed && seekSwipe) controller.endSeek(controller.state.value.seekValue)
                                    if (committed && !seekSwipe) controller.endSwipe()
                                    committed = false
                                }
                                if (pressed.size >= 2) {
                                    zoomAcc *= event.calculateZoom()
                                    controller.setZoomScale(baseZoom * zoomAcc)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (holdActive) continue // Fast-forwarding: ignore movement until release.
                            val c = pressed.first()
                            val d = c.position - start
                            if (!committed) {
                                if (abs(d.x) <= slopPx && abs(d.y) <= slopPx) continue
                                committed = true
                                seekSwipe = abs(d.x) > abs(d.y)
                                if (seekSwipe) {
                                    seekStartProgress = controller.state.value.progress
                                    controller.beginSeek(seekStartProgress)
                                    controller.showControls()
                                } else {
                                    controller.startSwipe(start.x, widthPx)
                                }
                            }
                            c.consume()
                            if (seekSwipe) {
                                val dur = controller.state.value.durationMs
                                if (dur > 0) {
                                    // Flutter: 300 ms per logical pixel.
                                    val deltaMs = (d.x / density.density) * 300f
                                    val newMs = (seekStartProgress * dur + deltaMs).coerceIn(0f, dur.toFloat())
                                    controller.updateSeek(newMs / dur)
                                }
                            } else {
                                // IgnoreConsumed: c was consumed just above, and positionChange() reports zero
                                // movement for a consumed change: brightness / volume never moved.
                                controller.updateSwipe(c.positionChangeIgnoreConsumed().y, heightPx)
                            }
                        }
                        if (pinching) {
                            pinchCooldown = true
                            scope.launch {
                                delay(150)
                                pinchCooldown = false
                            }
                        } else if (committed) {
                            if (seekSwipe) controller.endSeek(controller.state.value.seekValue) else controller.endSwipe()
                        }
                    }
                },
        ) {
            content()
        }

        flashForward?.let { forward ->
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart),
            ) {
                SeekFlash(flash.value, forward, seekInterval)
            }
        }

        if (holdFastForward) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    .background(Color(0xB8000000), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FastForward, null, Modifier.size(16.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("2×", style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W700, fontFamily = FontFamily.Monospace))
            }
        }
    }
}

// ── Seek flash (seek_flash.dart) ─────────────────────────────────────────────

/** Piecewise animation: [weights] split 0..1; each segment maps its slice to (from -> to). */
private fun seq(t: Float, vararg segs: Triple<Float, Float, Float>): Float {
    val total = segs.sumOf { it.first.toDouble() }.toFloat()
    var acc = 0f
    for ((w, from, to) in segs) {
        val end = acc + w / total
        if (t <= end) {
            val local = if (end > acc) (t - acc) / (end - acc) else 1f
            return from + (to - from) * local
        }
        acc = end
    }
    return segs.last().third
}

private fun interval(t: Float, begin: Float, end: Float): Float {
    val x = ((t - begin) / (end - begin)).coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x) // easeOut
}

private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1
    return 1 + c3 * (x - 1) * (x - 1) * (x - 1) + c1 * (x - 1) * (x - 1)
}

fun seekIcon(forward: Boolean, seconds: Int): ImageVector = if (forward) {
    when {
        seconds <= 5 -> Icons.Rounded.Forward5
        seconds <= 10 -> Icons.Rounded.Forward10
        else -> Icons.Rounded.Forward30
    }
} else {
    when {
        seconds <= 5 -> Icons.Rounded.Replay5
        seconds <= 10 -> Icons.Rounded.Replay10
        else -> Icons.Rounded.Replay30
    }
}

/** YouTube-style double-tap feedback: tint, three ripple rings, chevrons, icon, "+Ns" label. */
@Composable
private fun SeekFlash(t: Float, forward: Boolean, seekInterval: Int) {
    val bg = seq(t, Triple(15f, 0f, 1f), Triple(35f, 1f, 1f), Triple(50f, 1f, 0f))
    val rippleOpacity = seq(t, Triple(20f, 0.5f, 0.5f), Triple(80f, 0.5f, 0f))
    val r = listOf(interval(t, 0f, 0.75f), interval(t, 0.12f, 0.87f), interval(t, 0.24f, 0.99f))
    val iconPhase = t / 0.25f
    val iconScale = when {
        t < 0.25f -> 0.4f + (1.15f - 0.4f) * easeOutBack(iconPhase.coerceIn(0f, 1f))
        else -> seq(t, Triple(25f, 1.15f, 1.15f), Triple(10f, 1.15f, 1f), Triple(30f, 1f, 1f), Triple(35f, 1f, 0.6f))
    }
    val iconOpacity = seq(t, Triple(20f, 0f, 1f), Triple(45f, 1f, 1f), Triple(35f, 1f, 0f))
    val labelSlide = seq(t, Triple(25f, 8f, 0f), Triple(40f, 0f, 0f), Triple(35f, 0f, -6f))
    val chev = listOf(interval(t, 0.05f, 0.45f), interval(t, 0.15f, 0.55f), interval(t, 0.25f, 0.65f))
    val shape = if (forward) RoundedCornerShape(topStartPercent = 0, bottomStartPercent = 0, topEndPercent = 50, bottomEndPercent = 50)
    else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50, topEndPercent = 0, bottomEndPercent = 0)

    Box(Modifier.fillMaxSize().graphicsLayer { clip = true; this.shape = shape }) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = (bg * 0.22f).coerceIn(0f, 1f) }.background(
                Brush.horizontalGradient(
                    if (forward) listOf(Color(0x00FFFFFF), Color(0x33FFFFFF)) else listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)),
                ),
            ),
        )
        Canvas(Modifier.fillMaxSize()) {
            if (rippleOpacity <= 0f) return@Canvas
            val origin = Offset(if (forward) 0f else size.width, size.height / 2)
            val maxR = Offset(size.width, size.height / 2).getDistance()
            r.forEach { p ->
                if (p > 0f) {
                    val a = (rippleOpacity * (1f - p * 0.8f)).coerceIn(0f, 1f)
                    drawCircle(Color.White.copy(alpha = a), radius = p * maxR, center = origin, style = Stroke(2.dp.toPx()))
                }
            }
        }
        Row(Modifier.align(Alignment.Center).padding(bottom = 48.dp)) {
            val order = if (forward) chev else chev.reversed()
            order.forEach { p ->
                val dir = if (forward) 1f else -1f
                val a = if (p < 0.5f) p * 2f else (1f - p) * 2f
                Icon(
                    if (forward) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft,
                    null,
                    Modifier.size(26.dp).graphicsLayer {
                        translationX = dir * p * 28.dp.toPx()
                        alpha = a.coerceIn(0f, 1f)
                    },
                    tint = Color.White,
                )
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale; alpha = iconOpacity }) {
                SeekIconContent(forward, seekInterval, big = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (forward) "+${seekInterval}s" else "−${seekInterval}s",
                Modifier.graphicsLayer { translationY = labelSlide.dp.toPx(); alpha = iconOpacity },
                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.W700, letterSpacing = 0.5.sp),
            )
        }
    }
}

/** 15 s has no Material icon: fast-forward/rewind glyph with "15" under it (as in the Flutter build). */
@Composable
fun SeekIconContent(forward: Boolean, seconds: Int, big: Boolean, tint: Color = Color.White) {
    if (seconds == 15) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind, null, Modifier.size(if (big) 32.dp else 22.dp), tint = tint)
            Text("15", style = TextStyle(color = tint, fontSize = if (big) 12.sp else 9.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp))
        }
    } else {
        Icon(seekIcon(forward, seconds), null, Modifier.size(if (big) 44.dp else 28.dp), tint = tint)
    }
}
