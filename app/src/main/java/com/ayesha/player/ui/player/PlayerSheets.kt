package com.ayesha.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.BrandingWatermark
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayesha.player.core.theme.AppRadius
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.util.DurationFormatter
import com.ayesha.player.core.util.TrackLabels
import com.ayesha.player.core.util.VolumeColor
import com.ayesha.player.data.model.MediaTrack
import com.ayesha.player.ui.common.AppBottomSheet
import com.ayesha.player.ui.common.SheetHeader
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val mono = FontFamily.Monospace

// ── Speed + double-tap interval (speed_sheet.dart) ───────────────────────────

@Composable
fun SpeedSheet(speed: Float, seekInterval: Int, onSpeed: (Float) -> Unit, onSeekInterval: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var value by remember { mutableFloatStateOf(speed) }
    fun set(v: Float) {
        // Snap to 2 decimals so presets highlight exactly and the readout has no float tail.
        value = (v * 100).roundToInt() / 100f
        onSpeed(value)
    }
    AppBottomSheet(onDismiss) { closer ->
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PLAYBACK SPEED", style = AppTheme.text.label, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Refresh, "Reset", Modifier.size(20.dp).clip(CircleShape).clickable { set(1f) }, tint = c.textSecondary)
                Spacer(Modifier.width(12.dp))
                Text(String.format(Locale.ROOT, "%.2f×", value), color = c.textPrimary, fontWeight = FontWeight.W600, fontFamily = mono, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = ::set,
                valueRange = 0.25f..4f,
                steps = 74, // 0.05 steps
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = c.accent, inactiveTrackColor = c.elevated, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { p ->
                    Pill("${p}×", selected = abs(p - value) < 0.001f, shape = RoundedCornerShape(16.dp)) { set(p) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("DOUBLE-TAP TO SEEK", style = AppTheme.text.label)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(5, 10, 15, 30).forEach { s ->
                    Pill("${s}s", selected = s == seekInterval, shape = AppRadius.sm, mono = true, large = true) {
                        closer.close { onSeekInterval(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, shape: androidx.compose.ui.graphics.Shape, mono: Boolean = false, large: Boolean = false, onClick: () -> Unit) {
    val c = AppTheme.colors
    Text(
        label,
        Modifier
            .clip(shape)
            .background(if (selected) c.accent else c.elevated)
            .border(1.dp, if (selected) c.accent else c.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (large) 18.dp else 14.dp, vertical = if (large) 10.dp else 8.dp),
        color = if (selected) Color.White else c.textPrimary,
        fontWeight = FontWeight.W600,
        fontSize = 13.sp,
        fontFamily = if (mono) FontFamily.Monospace else null,
    )
}

// ── Volume 0-200 % (volume_sheet.dart) ───────────────────────────────────────

@Composable
fun VolumeSheet(volume: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var v by remember { mutableFloatStateOf(volume.coerceIn(0f, 200f)) }
    val boosted = v > 100f
    val accent = VolumeColor.forVolume(v, c.accent)
    fun set(x: Float) {
        v = x.coerceIn(0f, 200f)
        onChange(v)
    }
    AppBottomSheet(onDismiss) { closer ->
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (boosted) "VOLUME BOOST" else "VOLUME",
                    style = AppTheme.text.label.copy(color = if (boosted) VolumeColor.boost else AppTheme.text.label.color),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Done",
                    Modifier.clip(AppRadius.xl).background(c.elevated).border(1.dp, c.border, AppRadius.xl).clickable { closer.close {} }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                    color = c.textSecondary,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeOff, null, Modifier.size(20.dp), tint = c.textMuted)
                Slider(
                    value = v,
                    onValueChange = ::set,
                    valueRange = 0f..200f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = VolumeColor.barColor(v, c.accent), inactiveTrackColor = c.textDim),
                )
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(20.dp), tint = c.textMuted)
                Spacer(Modifier.width(12.dp))
                Text("${v.roundToInt()}%", Modifier.width(48.dp), color = accent, fontFamily = mono, fontWeight = FontWeight.W700, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(0f, 25f, 50f, 75f, 100f).forEach { p -> PresetButton("${p.roundToInt()}%", abs(v - p) < 1f, boost = false) { set(p); closer.close {} } }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(125f, 150f, 175f, 200f).forEach { p -> PresetButton("${p.roundToInt()}%", abs(v - p) < 1f, boost = true) { set(p); closer.close {} } }
            }
        }
    }
}

@Composable
private fun PresetButton(label: String, active: Boolean, boost: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val activeColor = if (boost) VolumeColor.boost else c.accent
    val activeSoft = if (boost) VolumeColor.boostSoft else c.accentSoft
    Text(
        label,
        Modifier
            .clip(AppRadius.sm)
            .background(if (active) activeSoft else Color.Transparent)
            .border(1.dp, if (active) activeColor else if (boost) VolumeColor.boost.copy(alpha = 0.2f) else c.border, AppRadius.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        fontFamily = mono,
        fontSize = 12.sp,
        fontWeight = FontWeight.W600,
        color = if (active) activeColor else if (boost) VolumeColor.boost.copy(alpha = 0.6f) else c.textMuted,
    )
}

// ── Audio tracks (audio_track_sheet.dart) ────────────────────────────────────

@Composable
fun AudioTrackSheet(tracks: List<MediaTrack>, selected: MediaTrack?, enabled: Boolean, onSelect: (MediaTrack) -> Unit, onDisable: () -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    // Fall back to the first track when the engine hasn't resolved a selection; none when disabled.
    val selectedIndex = if (!enabled) -1 else tracks.indexOfFirst { it.id == selected?.id }.let { if (it < 0) 0 else it }
    AppBottomSheet(onDismiss) { closer ->
        SheetHeader(Icons.Outlined.Audiotrack, "Audio Tracks")
        HorizontalDivider(color = c.divider)
        if (tracks.isEmpty()) {
            Text("No audio tracks found", Modifier.padding(24.dp), color = c.textMuted, fontSize = 13.sp)
        } else {
            TrackRow(Icons.AutoMirrored.Rounded.VolumeOff, "Disable", !enabled) { closer.close(onDisable) }
            tracks.forEachIndexed { i, t ->
                TrackRow(Icons.Outlined.Audiotrack, TrackLabels.trackLabel(t.title, t.language, i, tracks.size), i == selectedIndex) {
                    closer.close { onSelect(t) }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(icon: ImageVector, label: String, selected: Boolean, radio: Boolean = false, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (radio) (if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked) else icon,
            null,
            Modifier.size(18.dp),
            tint = if (selected) c.accent else c.textMuted,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            Modifier.weight(1f),
            color = if (selected) c.textPrimary else c.textSecondary,
            fontWeight = if (selected) FontWeight.W600 else FontWeight.Normal,
            fontSize = 13.sp,
        )
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = c.accent)
    }
}

// ── Subtitles (subtitle_sheet.dart) ──────────────────────────────────────────

@Composable
fun SubtitleSheet(
    tracks: List<MediaTrack>,
    selected: MediaTrack?,
    enabled: Boolean,
    delay: Float,
    style: SubtitleStyle,
    vm: PlayerViewModel,
    onSelect: (MediaTrack) -> Unit,
    onToggle: () -> Unit,
    onAdjustDelay: (Float) -> Unit,
    onResetDelay: () -> Unit,
    onLoadExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    AppBottomSheet(onDismiss) { closer ->
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Subtitles, null, Modifier.size(18.dp), tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Subtitles", Modifier.weight(1f), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (enabled) "ON" else "OFF",
                Modifier
                    .clip(AppRadius.sm)
                    .background(if (enabled) c.accentSoft else c.elevated)
                    .border(1.dp, if (enabled) c.accent else c.border, AppRadius.sm)
                    .clickable { closer.close(onToggle) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                color = if (enabled) c.accent else c.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = mono,
                letterSpacing = 1.sp,
            )
        }
        HorizontalDivider(color = c.divider)
        DelayControl(delay, onAdjustDelay, onResetDelay)
        HorizontalDivider(color = c.divider)
        AppearanceControl(style, vm)
        HorizontalDivider(color = c.divider)
        if (tracks.isEmpty()) {
            Text(
                "No subtitle tracks in this file.\nLoad a subtitle file (.srt, .vtt, .ass) using the button below.",
                Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        } else {
            tracks.forEachIndexed { i, t ->
                // "English", not "eng" (as the audio sheet does); no "Default" for a lone subtitle.
                val label = t.title?.trim()?.takeIf { it.isNotEmpty() } ?: TrackLabels.languageName(t.language) ?: "Track ${i + 1}"
                TrackRow(Icons.Rounded.Subtitles, label, enabled && selected?.id == t.id, radio = true) { closer.close { onSelect(t) } }
            }
        }
        OutlinedButton(
            onClick = { closer.close(onLoadExternal) },
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
            shape = AppRadius.sm,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.border),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text("Load external subtitle", color = c.accent, fontSize = 12.sp, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun DelayControl(delay: Float, onAdjust: (Float) -> Unit, onReset: () -> Unit) {
    val c = AppTheme.colors
    var local by remember { mutableFloatStateOf(delay) }
    val label = if (local == 0f) "0.0s" else (if (local > 0) "+" else "") + String.format(Locale.ROOT, "%.1fs", local)
    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AvTimer, null, Modifier.size(18.dp), tint = c.textMuted)
        Spacer(Modifier.width(14.dp))
        Text("Sync delay", Modifier.weight(1f), color = c.textSecondary, fontSize = 13.sp)
        if (local != 0f) {
            Text("Reset", Modifier.clickable { local = 0f; onReset() }.padding(end = 12.dp), color = c.textMuted, fontSize = 11.sp)
        }
        StepButton(Icons.Rounded.Remove) { local = (local - 0.5f).coerceIn(-60f, 60f); onAdjust(-0.5f) }
        Text(label, Modifier.width(56.dp), textAlign = TextAlign.Center, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.W600, fontFamily = mono)
        StepButton(Icons.Rounded.Add) { local = (local + 0.5f).coerceIn(-60f, 60f); onAdjust(0.5f) }
    }
}

@Composable
private fun AppearanceControl(style: SubtitleStyle, vm: PlayerViewModel) {
    val c = AppTheme.colors
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3A3D44), Color(0xFF1A1C20))))
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .padding(vertical = 32.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Subtitle Preview",
                Modifier.then(if (style.background) Modifier.background(style.backgroundColor) else Modifier),
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = style.fontSize.sp,
                    lineHeight = (style.fontSize * 1.4f).sp,
                    color = style.color,
                    fontFamily = style.fontFamily,
                    fontWeight = FontWeight.Bold,
                    shadow = if (style.background) null else Shadow(Color.Black, blurRadius = 8f),
                ),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FormatSize, null, Modifier.size(18.dp), tint = c.textMuted)
            Spacer(Modifier.width(14.dp))
            Text("Font size", Modifier.weight(1f), color = c.textSecondary, fontSize = 13.sp)
            StepButton(Icons.Rounded.Remove) { vm.adjustSubtitleFont(-4f) }
            Text(style.fontSize.roundToInt().toString(), Modifier.width(40.dp), textAlign = TextAlign.Center, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.W600, fontFamily = mono)
            StepButton(Icons.Rounded.Add) { vm.adjustSubtitleFont(4f) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.TextFields, null, Modifier.size(18.dp), tint = c.textMuted)
            Spacer(Modifier.width(14.dp))
            Text("Font", color = c.textSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        // Each chip renders its label in its own family: a live preview.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleFontPresets.forEachIndexed { i, f ->
                val on = style.fontIndex == i
                Text(
                    f.label,
                    Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(if (on) c.accentSoft else c.elevated)
                        .border(if (on) 1.5.dp else 1.dp, if (on) c.accent else c.border, RoundedCornerShape(19.dp))
                        .clickable { vm.setSubtitleFont(i) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    fontFamily = f.family,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W600,
                    color = if (on) c.accent else c.textPrimary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SwatchRow(Icons.Outlined.Palette, "Color", SubtitleColorPresets, style.colorIndex, vm::setSubtitleColor)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.BrandingWatermark, null, Modifier.size(18.dp), tint = c.textMuted)
            Spacer(Modifier.width(14.dp))
            Text("Background", Modifier.weight(1f), color = c.textSecondary, fontSize = 13.sp)
            Switch(style.background, vm::setSubtitleBackground, colors = SwitchDefaults.colors(checkedThumbColor = c.accent, checkedTrackColor = c.accentSoft))
        }
        if (style.background) {
            Spacer(Modifier.height(12.dp))
            // Presets carry alpha for the video; shown solid here so swatches read clearly.
            SwatchRow(null, "Background color", SubtitleBgColorPresets.map { it.copy(alpha = 1f) }, style.backgroundColorIndex, vm::setSubtitleBgColor)
        }
    }
}

@Composable
private fun SwatchRow(icon: ImageVector?, label: String, colors: List<Color>, selected: Int, onSelect: (Int) -> Unit) {
    val c = AppTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = c.textMuted)
                Spacer(Modifier.width(14.dp))
            } else {
                Spacer(Modifier.width(32.dp))
            }
            Text(label, color = c.textSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            colors.forEachIndexed { i, col ->
                Box(
                    Modifier
                        .size(22.dp)
                        .background(col, CircleShape)
                        .border(if (i == selected) 2.dp else 1.dp, if (i == selected) c.accent else c.border, CircleShape)
                        .clickable { onSelect(i) },
                )
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(c.elevated).border(1.dp, c.border, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = c.textSecondary)
    }
}

// ── Sleep timer (sleep_timer_sheet.dart) ─────────────────────────────────────

@Composable
fun SleepTimerSheet(endsAt: Long?, endOfVideo: Boolean, onSet: (Long) -> Unit, onEndOfVideo: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var custom by remember { mutableIntStateOf(60) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val active = endsAt != null || endOfVideo
    AppBottomSheet(onDismiss) { closer ->
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp), tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Sleep timer", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
            if (active) {
                Text(
                    if (endOfVideo) "End of video" else endsAt?.let { DurationFormatter.formatMs((it - now).coerceAtLeast(0)) }.orEmpty(),
                    color = c.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W600,
                    fontFamily = mono,
                )
            }
        }
        HorizontalDivider(color = c.divider)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = c.textMuted)
            Spacer(Modifier.width(14.dp))
            Text("Custom", Modifier.weight(1f), color = c.textSecondary, fontSize = 13.sp)
            StepButton(Icons.Rounded.Remove) { if (custom > 5) custom -= 5 }
            Text("${custom}m", Modifier.width(50.dp), textAlign = TextAlign.Center, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.W600, fontFamily = mono)
            StepButton(Icons.Rounded.Add) { custom += 5 }
            Spacer(Modifier.width(16.dp))
            Text(
                "SET",
                Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent).clickable { closer.close { onSet(custom * 60_000L) } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
        HorizontalDivider(color = c.divider)
        listOf(15, 30, 45, 60, 90).forEach { m ->
            SleepRow("$m minutes", Icons.Outlined.Timer) { closer.close { onSet(m * 60_000L) } }
        }
        SleepRow("End of video", Icons.Outlined.Movie, selected = endOfVideo) { closer.close(onEndOfVideo) }
        if (active) SleepRow("Turn off", Icons.Rounded.Close, danger = true) { closer.close(onCancel) }
    }
}

@Composable
private fun SleepRow(label: String, icon: ImageVector, selected: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val c = AppTheme.colors
    val red = Color(0xFFE5534B)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (danger) red else if (selected) c.accent else c.textMuted)
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            Modifier.weight(1f),
            color = if (danger) red else if (selected) c.accent else c.textSecondary,
            fontWeight = if (selected) FontWeight.W600 else FontWeight.Normal,
            fontSize = 13.sp,
        )
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = c.accent)
    }
}
