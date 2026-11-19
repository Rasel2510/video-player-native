package com.ayesha.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ayesha.player.ui.common.EmphasizedAccelerate
import com.ayesha.player.ui.common.EmphasizedDecelerate
import com.ayesha.player.ui.common.Fade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.theme.PlayerControlsStyle
import com.ayesha.player.core.util.VolumeColor
import com.ayesha.player.playback.SwipeGesture
import com.ayesha.player.ui.common.GlassSurface
import com.ayesha.player.ui.common.sharedVideoThumb
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** subtitle_overlay.dart: styled cue text, draggable; the drop position is persisted. */
@Composable
fun SubtitleOverlay(cue: String, style: SubtitleStyle, onDragEnd: (xDp: Float, yDp: Float) -> Unit) {
    if (cue.isEmpty()) return
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val restX = style.offsetX?.value ?: 0f
    val restY = style.offsetY?.value ?: 0f
    val animX by animateDpAsState(restX.dp, tween(if (dragging) 0 else 300), label = "subX")
    val animY by animateDpAsState(restY.dp, tween(if (dragging) 0 else 300), label = "subY")
    val x = if (dragging) dragX.dp else animX
    val y = if (dragging) dragY.dp else animY

    Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .offset(x, y)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            dragX = style.offsetX?.value ?: 0f
                            dragY = style.offsetY?.value ?: 0f
                        },
                        onDragEnd = {
                            dragging = false
                            onDragEnd(dragX, dragY)
                        },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        val maxX = config.screenWidthDp * 0.8f
                        val maxY = config.screenHeightDp * 0.8f
                        dragX = (dragX + amount.x / density.density).coerceIn(-maxX, maxX)
                        dragY = (dragY + amount.y / density.density).coerceIn(-maxY, maxY)
                    }
                }
                .then(
                    if (style.background) {
                        Modifier.background(style.backgroundColor, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(
                cue,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = style.fontSize.sp,
                    lineHeight = (style.fontSize * 1.3f).sp,
                    color = style.color,
                    fontFamily = style.fontFamily,
                    fontWeight = FontWeight.Bold,
                    shadow = if (style.background) null else Shadow(Color.Black, blurRadius = 8f),
                ),
            )
        }
    }
}

private val BrightnessColor = Color(0xFFFFE066)

/**
 * swipe_hud.dart. [value]: brightness 0..1, or volume/200 (0.5 = 100 %). Fades in quickly and
 * out gently; while fading out it keeps the look of the gesture that just ended.
 */
@Composable
fun SwipeHud(gesture: SwipeGesture, value: Float) {
    // Plain holder, not state: only remembers what to draw once the gesture is over.
    val last = remember { arrayOf(SwipeGesture.Brightness) }
    if (gesture != SwipeGesture.None) last[0] = gesture
    val brightness = last[0] == SwipeGesture.Brightness
    Box(Modifier.fillMaxSize(), contentAlignment = if (brightness) Alignment.CenterStart else Alignment.CenterEnd) {
        Fade(gesture != SwipeGesture.None, inMs = 90, outMs = 320) {
            SwipePill(brightness, value)
        }
    }
}

@Composable
private fun SwipePill(brightness: Boolean, value: Float) {
    val accent = AppTheme.colors.accent
    val color = if (brightness) BrightnessColor else VolumeColor.forVolume(value * 200f, accent)
    val fill = if (brightness) BrightnessColor else VolumeColor.barColor(value * 200f, accent)
    val icon = when {
        brightness && value > 0.6f -> Icons.Rounded.BrightnessHigh
        brightness && value > 0.3f -> Icons.Rounded.BrightnessMedium
        brightness -> Icons.Rounded.BrightnessLow
        value > 0.6f -> Icons.AutoMirrored.Rounded.VolumeUp
        value > 0f -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeOff
    }
    val percent = if (brightness) "${(value * 100).roundToInt()}%" else "${(value * 200).roundToInt()}%"
    // 0-100 %: the bar fills; above 100 % it stays full and only its color heats up.
    val base = (if (brightness) value else value * 2).coerceIn(0f, 1f)

    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .width(44.dp)
            .background(Color(0xB8000000), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(22.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(4.dp, 90.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x2EFFFFFF))) {
            // Drawn, not laid out: the bar follows the finger without a relayout per move.
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height * base
                drawRect(fill, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(percent, style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.W700, fontFamily = FontFamily.Monospace))
    }
}

/**
 * auto_play_countdown.dart. Slides up into place; the ring drains smoothly toward the next
 * second instead of jumping once per second. Keeps its last content while sliding away.
 */
@Composable
fun AutoPlayCountdown(countdown: Int?, nextName: String?, onPlayNow: () -> Unit, onCancel: () -> Unit) {
    val lastCount = remember { intArrayOf(5) }
    val lastName = remember { arrayOf("") }
    val visible = countdown != null && nextName != null
    if (countdown != null && nextName != null) {
        lastCount[0] = countdown
        lastName[0] = nextName
    }
    Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 100.dp), contentAlignment = Alignment.BottomCenter) {
        Fade(visible, inMs = 200, outMs = 180) {
            CountdownCard(
                lastCount[0],
                lastName[0],
                onPlayNow,
                onCancel,
                Modifier.animateEnterExit(
                    enter = slideInVertically(tween(340, easing = EmphasizedDecelerate)) { it / 2 },
                    exit = slideOutVertically(tween(200, easing = EmphasizedAccelerate)) { it / 3 },
                ),
            )
        }
    }
}

@Composable
private fun CountdownCard(countdown: Int, nextName: String, onPlayNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val accent = AppTheme.colors.accent
    val ring = remember { Animatable(countdown / 5f) }
    LaunchedEffect(countdown) {
        ring.animateTo(((countdown - 1).coerceAtLeast(0)) / 5f, tween(1000, easing = LinearEasing))
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xD1000000), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                // Read while drawing: the ring animates without recomposing the card.
                progress = { ring.value },
                modifier = Modifier.fillMaxSize(),
                color = accent,
                trackColor = Color(0x1FFFFFFF),
                strokeWidth = 2.5.dp,
            )
            Text("$countdown", style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.W700, fontFamily = FontFamily.Monospace))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Up next", style = TextStyle(color = Color(0x8AFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.W500, letterSpacing = 0.3.sp))
            Spacer(Modifier.height(2.dp))
            Text(nextName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W500))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "Play",
            Modifier.clip(RoundedCornerShape(8.dp)).background(accent).clickable(onClick = onPlayNow).padding(horizontal = 12.dp, vertical = 7.dp),
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.W700),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1AFFFFFF)).clickable(onClick = onCancel).padding(7.dp)) {
            Icon(Icons.Rounded.Close, "Cancel", Modifier.size(16.dp), tint = Color(0xB3FFFFFF))
        }
    }
}

/** zoom_indicator_overlay.dart: "1.8× ✕" for 2 s after a zoom change; tap resets. */
@Composable
fun ZoomIndicator(zoom: Float, onReset: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(zoom) {
        if (abs(zoom - 1f) < 0.05f) {
            visible = false
        } else {
            visible = true
            delay(2000)
            visible = false
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible && abs(zoom - 1f) >= 0.05f, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xA6000000))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                    .clickable {
                        onReset()
                        visible = false
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ZoomIn, null, Modifier.size(16.dp), tint = Color(0xB3FFFFFF))
                Spacer(Modifier.width(6.dp))
                Text(String.format(Locale.ROOT, "%.1f×", zoom), style = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Close, null, Modifier.size(14.dp), tint = Color(0x8AFFFFFF))
            }
        }
    }
}

/**
 * player_lock_layer.dart: while locked, swallows every touch; a tap shows the unlock button
 * for 2 s (local state only, so nothing else recomposes).
 */
@Composable
fun LockLayer(locked: Boolean, style: PlayerControlsStyle, onUnlock: () -> Unit) {
    if (!locked) return
    var iconVisible by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        iconVisible = true
        delay(2000)
        iconVisible = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { tick++ }) }
            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume() } },
    ) {
        AnimatedVisibility(iconVisible, Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(start = 20.dp), enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            GlassSurface(style, Modifier.clickable(onClick = onUnlock)) {
                Icon(Icons.Rounded.Lock, "Unlock", Modifier.padding(12.dp).size(22.dp), tint = Color.White)
            }
        }
    }
}

/** error_state.dart. */
@Composable
fun ErrorState(message: String?, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = AppTheme.colors
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).background(c.errorBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(30.dp), tint = c.errorRed)
            }
            Spacer(Modifier.height(20.dp))
            Text("Unable to play video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.W600)
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = Color(0x8AFFFFFF), fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xB3FFFFFF)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3DFFFFFF)),
                ) { Text("Go back") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onRetry, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
    }
}

/**
 * loading_poster.dart: the cached thumbnail behind a spinner until the first frame, then a
 * short fade that reveals the video underneath.
 */
@Composable
fun LoadingPoster(visible: Boolean, poster: File?, path: String?) {
    Fade(visible, inMs = 150, outMs = 240) {
        // Opaque: the video surface is a hole in the window, and while the screen is still
        // fading in, that hole would show the list underneath instead of black. Fading this out
        // crossfades from the poster to the first frame.
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (poster != null) {
                // The Hero target: the tapped card's thumbnail grows into this.
                AsyncImage(
                    poster,
                    null,
                    (if (path != null) Modifier.sharedVideoThumb(path) else Modifier).fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            // Most videos show their first frame within a few hundred ms: a spinner right away
            // only made the open feel like waiting. It appears only when loading is really slow.
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SPINNER_DELAY_MS)
                slow = true
            }
            Fade(slow, inMs = 250) {
                CircularProgressIndicator(color = AppTheme.colors.accent)
            }
        }
    }
}

private const val SPINNER_DELAY_MS = 700L
