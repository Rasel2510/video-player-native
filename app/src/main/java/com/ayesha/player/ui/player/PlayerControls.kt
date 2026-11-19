package com.ayesha.player.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.FeaturedVideo
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.theme.PlayerControlsStyle
import com.ayesha.player.core.util.DurationFormatter
import com.ayesha.player.playback.LoopMode
import com.ayesha.player.playback.PlayerUiState
import com.ayesha.player.playback.RotationMode
import com.ayesha.player.ui.common.EmphasizedAccelerate
import com.ayesha.player.ui.common.EmphasizedDecelerate
import com.ayesha.player.ui.common.Fade
import com.ayesha.player.ui.common.pick
import java.util.Locale

private val White90 = Color(0xE6FFFFFF)
private val White60 = Color(0x99FFFFFF)
private val White30 = Color(0x4DFFFFFF)
private val White12 = Color(0x1FFFFFFF)
private val Orange = Color(0xFFFF8C00)

/** Everything the controls can ask for (player_controls_overlay.dart's callbacks). */
interface PlayerActions {
    fun back()
    fun togglePlay()
    fun cycleFit()
    fun showSpeed()
    fun showVolume()
    fun showAudio()
    fun showSubtitles()
    fun seekBack()
    fun seekForward()
    fun cycleRotation()
    fun seekStart(v: Float)
    fun seekUpdate(v: Float)
    fun seekEnd(v: Float)
    fun next()
    fun previous()
    fun lock()
    fun cycleLoop()
    fun audioMode()
    fun sleepTimer()
    fun pip()
    fun cycleAb()
    fun miniPlayer()
}

/** 2× / 1.25× / 1.2×: never a float tail. */
fun speedLabel(speed: Float): String {
    if (speed == speed.toInt().toFloat()) return "${speed.toInt()}×"
    var s = String.format(Locale.ROOT, "%.2f", speed)
    if (s.endsWith('0')) s = s.dropLast(1)
    return "$s×"
}

/**
 * The controls layer: fades in and out (no offscreen layer, see [Fade]) while the top bar
 * drops in from above, the bottom bar rises from below and the center buttons settle from a
 * slightly smaller size.
 *
 * Every part reads only the fields it shows from [st], so a position tick redraws the time
 * and the slider, not every button.
 */
@Composable
fun PlayerControls(visible: Boolean, st: State<PlayerUiState>, style: PlayerControlsStyle, a: PlayerActions) {
    Fade(visible, inMs = 160, outMs = 220) {
        Box(Modifier.fillMaxSize()) {
            // Eased three-stop scrims so there's no hard band over bright frames.
            Box(
                Modifier.fillMaxWidth().height(180.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(0f to Color(0xD9000000), 0.55f to Color(0x4D000000), 1f to Color.Transparent)),
            )
            Box(
                Modifier.fillMaxWidth().height(200.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x4D000000), 1f to Color(0xD9000000))),
            )
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopBar(
                    st, style, a,
                    Modifier.animateEnterExit(
                        enter = slideInVertically(tween(300, easing = EmphasizedDecelerate)) { -it / 3 },
                        exit = slideOutVertically(tween(220, easing = EmphasizedAccelerate)) { -it / 4 },
                    ),
                )
                Spacer(Modifier.weight(1f))
                CenterControls(
                    st, style, a,
                    Modifier.animateEnterExit(
                        enter = scaleIn(tween(300, easing = EmphasizedDecelerate), initialScale = 0.9f),
                        exit = scaleOut(tween(220, easing = EmphasizedAccelerate), targetScale = 0.94f),
                    ),
                )
                Spacer(Modifier.weight(1f))
                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .animateEnterExit(
                            enter = slideInVertically(tween(300, easing = EmphasizedDecelerate)) { it / 3 },
                            exit = slideOutVertically(tween(220, easing = EmphasizedAccelerate)) { it / 4 },
                        ),
                ) {
                    ProgressRow(st, a)
                    Spacer(Modifier.height(8.dp))
                    ActionsRow(st, style, a)
                }
            }
        }
    }
}

private enum class VolumeLevel { Off, Low, High, Boosted }

@Composable
private fun TopBar(st: State<PlayerUiState>, style: PlayerControlsStyle, a: PlayerActions, modifier: Modifier) {
    val accent = AppTheme.colors.accent
    val title = st.pick { it.title }
    val ab = st.pick { if (it.abRepeatStartMs == null) 0 else if (it.abRepeatEndMs == null) 1 else 2 }
    val subsOn = st.pick { it.subtitlesEnabled && it.subtitleTracks.isNotEmpty() }
    val speed = st.pick { it.playbackSpeed }
    // Bucketed: dragging the volume doesn't redraw the bar for every percent.
    val volume = st.pick {
        when {
            it.volume == 0f -> VolumeLevel.Off
            it.volume < 50f -> VolumeLevel.Low
            it.volume > 100f -> VolumeLevel.Boosted
            else -> VolumeLevel.High
        }
    }
    val multiAudio = st.pick { it.audioTracks.size > 1 }
    val sleepOn = st.pick { it.sleepTimerEndsAt != null || it.sleepTimerEndOfVideo }
    val loop = st.pick { it.loopMode }
    Column(modifier.padding(start = 10.dp, top = 8.dp, end = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Rounded.ArrowBackIosNew, 20.dp, onClick = a::back)
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W500, letterSpacing = 0.1.sp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) {
            // One glass pill for the whole row: the icons inside draw no surface of their own.
            GlassSurfaceRow(style) {
                Chip(speedLabel(speed), onClick = a::showSpeed)
                Spacer(Modifier.width(6.dp))
                IconBtn(
                    when (volume) {
                        VolumeLevel.Off -> Icons.AutoMirrored.Rounded.VolumeOff
                        VolumeLevel.Low -> Icons.AutoMirrored.Rounded.VolumeDown
                        else -> Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    20.dp,
                    tint = if (volume == VolumeLevel.Boosted) Orange else Color.White,
                    onClick = a::showVolume,
                )
                IconBtn(Icons.Rounded.Audiotrack, 19.dp, tint = if (multiAudio) accent else Color.White, onClick = a::showAudio)
                IconBtn(Icons.Rounded.Headphones, 19.dp, onClick = a::audioMode)
                IconBtn(Icons.Rounded.Bedtime, 18.dp, tint = if (sleepOn) accent else Color.White, onClick = a::sleepTimer)
                IconBtn(
                    if (subsOn) Icons.Rounded.Subtitles else Icons.Outlined.SubtitlesOff,
                    19.dp,
                    tint = if (subsOn) accent else Color.White,
                    onClick = a::showSubtitles,
                )
                Spacer(Modifier.width(6.dp))
                Chip(if (ab == 1) "A•" else "A-B", outline = if (ab == 0) null else accent, onClick = a::cycleAb)
                Spacer(Modifier.width(6.dp))
                IconBtn(
                    if (loop == LoopMode.LoopOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    19.dp,
                    tint = when (loop) {
                        LoopMode.None -> Color.White
                        LoopMode.LoopAll -> accent
                        LoopMode.LoopOne -> Orange
                    },
                    onClick = a::cycleLoop,
                )
                IconBtn(Icons.AutoMirrored.Rounded.FeaturedVideo, 19.dp, onClick = a::miniPlayer)
            }
        }
    }
}

@Composable
private fun GlassSurfaceRow(style: PlayerControlsStyle, content: @Composable RowScope.() -> Unit) {
    com.ayesha.player.ui.common.GlassSurface(style, shape = RoundedCornerShape(24.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun CenterControls(st: State<PlayerUiState>, style: PlayerControlsStyle, a: PlayerActions, modifier: Modifier) {
    val hasPrevious = st.pick { it.hasPrevious }
    val hasNext = st.pick { it.hasNext }
    val interval = st.pick { it.seekInterval }
    val playing = st.pick { it.intendsToPlay }
    val siblings = hasPrevious || hasNext
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (siblings) {
            GlassButton(style, 42.dp, enabled = hasPrevious, onClick = a::previous) {
                Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(26.dp), tint = if (hasPrevious) White90 else White12)
            }
            Spacer(Modifier.width(12.dp))
        }
        GlassButton(style, 46.dp, onClick = a::seekBack) { SeekIconContent(false, interval, big = false, tint = White90) }
        Spacer(Modifier.width(16.dp))
        // Follows the playback intent, so it doesn't flicker during a seek's re-buffer.
        GlassButton(style, 62.dp, strong = true, shadow = true, onClick = a::togglePlay) {
            AnimatedContent(
                playing,
                transitionSpec = { (fadeIn(tween(150)) + scaleIn(tween(300), 0.7f)) togetherWith (fadeOut(tween(150)) + scaleOut(tween(300), 0.7f)) },
                label = "playPause",
            ) { on ->
                Icon(if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (on) "Pause" else "Play", Modifier.size(34.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.width(16.dp))
        GlassButton(style, 46.dp, onClick = a::seekForward) { SeekIconContent(true, interval, big = false, tint = White90) }
        if (siblings) {
            Spacer(Modifier.width(12.dp))
            GlassButton(style, 42.dp, enabled = hasNext, onClick = a::next) {
                Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(26.dp), tint = if (hasNext) White90 else White12)
            }
        }
    }
}

private val TimeStyleBase = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp)

/** The only part of the controls that changes with every position tick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressRow(st: State<PlayerUiState>, a: PlayerActions) {
    val accent = AppTheme.colors.accent
    val position = st.pick { it.positionMs }
    val duration = st.pick { it.durationMs }
    val progress = st.pick { it.progress.coerceIn(0f, 1f) }
    val elapsedStyle = remember(accent) { TimeStyleBase.copy(color = accent, fontWeight = FontWeight.W700) }
    val remainingStyle = remember { TimeStyleBase.copy(color = White60) }
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = White30)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(DurationFormatter.formatMs(position), style = elapsedStyle)
        val interaction = remember { MutableInteractionSource() }
        var dragging by remember { mutableStateOf(false) }
        Slider(
            value = progress,
            onValueChange = {
                if (!dragging) {
                    dragging = true
                    a.seekStart(it)
                }
                a.seekUpdate(it)
            },
            onValueChangeFinished = {
                dragging = false
                a.seekEnd(st.value.seekValue)
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            interactionSource = interaction,
            colors = sliderColors,
            thumb = {
                Box(Modifier.size(13.dp).background(Color.White, CircleShape))
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(3.dp),
                    colors = sliderColors,
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
        )
        Text("−" + DurationFormatter.formatMs((duration - position).coerceAtLeast(0)), style = remainingStyle)
    }
}

@Composable
private fun ActionsRow(st: State<PlayerUiState>, style: PlayerControlsStyle, a: PlayerActions) {
    val fit = st.pick { it.fitMode }
    val rotation = st.pick { it.rotationMode }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassButton(style, 40.dp, onClick = a::lock) { Icon(Icons.Rounded.LockOpen, "Lock", Modifier.size(20.dp), tint = Color.White) }
        Spacer(Modifier.weight(1f))
        GlassButton(style, 40.dp, onClick = a::pip) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", Modifier.size(20.dp), tint = Color.White) }
        Spacer(Modifier.width(10.dp))
        com.ayesha.player.ui.common.GlassSurface(style, Modifier.height(32.dp).clickable(onClick = a::cycleFit), shape = RoundedCornerShape(16.dp)) {
            // Crossfades FIT -> CROP -> FILL -> AUTO.
            AnimatedContent(fit, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "fit") { f ->
                Text(f.label, Modifier.padding(horizontal = 13.dp), style = chipText(Color.White))
            }
        }
        Spacer(Modifier.width(10.dp))
        GlassButton(style, 40.dp, onClick = a::cycleRotation) {
            Icon(
                when (rotation) {
                    RotationMode.Auto -> Icons.Rounded.ScreenRotation
                    RotationMode.Landscape -> Icons.Rounded.StayCurrentLandscape
                    RotationMode.Portrait -> Icons.Rounded.StayCurrentPortrait
                },
                "Rotation",
                Modifier.size(22.dp),
                tint = Color.White,
            )
        }
    }
}

private fun chipText(color: Color) = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.W700, letterSpacing = 0.6.sp)

/** A circular glass button. */
@Composable
private fun GlassButton(
    style: PlayerControlsStyle,
    size: Dp,
    enabled: Boolean = true,
    strong: Boolean = false,
    shadow: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    com.ayesha.player.ui.common.GlassSurface(
        style,
        Modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        strong = strong,
        withShadow = shadow,
    ) { content() }
}

/** An icon inside the top pill: no surface of its own. */
@Composable
private fun IconBtn(icon: ImageVector, size: Dp, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, null, Modifier.size(size), tint = tint)
    }
}

/** A text chip inside the top pill, optionally outlined when active. */
@Composable
private fun Chip(label: String, outline: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (outline != null) Modifier.border(1.dp, outline, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = chipText(outline ?: Color.White))
    }
}
