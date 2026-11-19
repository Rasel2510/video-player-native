package com.ayesha.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.theme.LibraryAccentPresets
import com.ayesha.player.core.theme.LibraryCardStyle
import com.ayesha.player.core.theme.PlayerControlsStyle
import com.ayesha.player.core.theme.resolveLibraryAccent
import com.ayesha.player.data.device.StoragePermissions
import com.ayesha.player.data.prefs.AppearanceSettings
import com.ayesha.player.data.prefs.LibraryScanMode
import com.ayesha.player.data.prefs.PlaybackSettings
import com.ayesha.player.ui.common.AppBottomSheet
import com.ayesha.player.ui.common.NewBadgeChip
import com.ayesha.player.ui.common.SheetHeader

/** menu_sheet.dart: open file, Secure Vault, toggles, accent colors. */
@Composable
fun MenuSheet(
    appearance: AppearanceSettings,
    playback: PlaybackSettings,
    vm: LibraryViewModel,
    onOpenFile: () -> Unit,
    onOpenVault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    AppBottomSheet(onDismiss) { closer ->
        SheetHeader(Icons.Rounded.Settings, "Settings")
        HorizontalDivider(color = c.divider)

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { closer.close(onOpenFile) }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(24.dp), tint = c.textSecondary)
            Spacer(Modifier.width(16.dp))
            Text("Open file...", fontSize = 16.sp, color = c.textPrimary)
        }


        // Gated by the vault's own PIN (set up on first visit), not the device's screen lock.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { closer.close(onOpenVault) }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(24.dp), tint = c.textSecondary)
            Spacer(Modifier.width(16.dp))
            Text("Secure Vault", fontSize = 16.sp, color = c.textPrimary)
        }

        ToggleRow(
            Icons.Rounded.HistoryToggleOff, Icons.Rounded.History,
            "Continue Watching", "Show your recently watched videos",
            playback.continueWatchingEnabled,
        ) { vm.toggleContinueWatching(playback.continueWatchingEnabled) }
        ToggleRow(
            Icons.Rounded.BlurOff, Icons.Rounded.BlurOn,
            "Frosted player controls", "Blur the video behind the player buttons",
            appearance.controlsStyle == PlayerControlsStyle.Frosted,
        ) { vm.toggleControlsFrosted(appearance.controlsStyle) }
        ToggleRow(
            Icons.Outlined.Dashboard, Icons.Rounded.Dashboard,
            "Tinted card style", "Glassy translucent folder & video cards",
            appearance.cardStyle == LibraryCardStyle.Tinted,
        ) { vm.toggleCardTinted(appearance.cardStyle) }

        HorizontalDivider(color = c.divider)

        AccentColorRow("Folder icon color", appearance.folderIconColorIndex, vm::setFolderIconColor) { color ->
            Icon(Icons.Rounded.Folder, null, Modifier.size(22.dp), tint = color)
        }
        AccentColorRow("New badge color", appearance.newBadgeColorIndex, vm::setNewBadgeColor) { color ->
            NewBadgeChip(color)
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    activeIcon: ImageVector,
    label: String,
    subtitle: String,
    value: Boolean,
    onToggle: () -> Unit,
) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (value) activeIcon else icon, null, Modifier.size(24.dp), tint = if (value) c.accent else c.textSecondary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 16.sp,
                color = if (value) c.textPrimary else c.textSecondary,
                fontWeight = if (value) FontWeight.W500 else FontWeight.W400,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = c.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = value,
            onCheckedChange = null, // the whole row toggles
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.surface,
                checkedTrackColor = c.accent,
                uncheckedThumbColor = c.surface,
                uncheckedTrackColor = c.border,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}

/** A preset picker: preview + label, then a scrollable swatch strip that fades at the end while more is hidden. */
@Composable
private fun AccentColorRow(label: String, selectedIndex: Int, onSelect: (Int) -> Unit, leading: @Composable (Color) -> Unit) {
    val c = AppTheme.colors
    // The "Theme" preset falls back to folderIcon, same as the folder card and the badges.
    val themeDefault = c.folderIcon
    val scroll = rememberScrollState()
    val canScroll = scroll.maxValue > 0 && scroll.value < scroll.maxValue
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading(resolveLibraryAccent(selectedIndex, themeDefault))
            Spacer(Modifier.width(16.dp))
            Text(label, fontSize = 15.sp, color = c.textPrimary)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (canScroll) {
                        drawRect(
                            Brush.horizontalGradient(0.9f to Color.White, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .horizontalScroll(scroll)
                .padding(end = if (scroll.maxValue > 0) 28.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryAccentPresets.forEachIndexed { i, preset ->
                val color = resolveLibraryAccent(i, themeDefault)
                val selected = i == selectedIndex
                val isTheme = preset.color == null
                Box(
                    Modifier
                        .size(22.dp)
                        .background(color, CircleShape)
                        .border(
                            if (selected) 2.dp else 1.dp,
                            when {
                                selected -> c.textPrimary
                                isTheme -> color
                                else -> c.border
                            },
                            CircleShape,
                        )
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isTheme) Icon(Icons.Rounded.Brightness6, null, Modifier.size(12.dp), tint = c.bg)
                }
            }
        }
    }
}

/** scan_mode_sheet.dart, plus a way to grant "All files access" when a mode needs it. */
@Composable
fun ScanModeSheet(selected: LibraryScanMode, onSelect: (LibraryScanMode) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val context = LocalContext.current
    AppBottomSheet(onDismiss) { closer ->
        SheetHeader(Icons.Rounded.TravelExplore, "Library scan mode")
        HorizontalDivider(color = c.divider)
        LibraryScanMode.entries.forEach { mode ->
            val on = mode == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { closer.close { onSelect(mode) } }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Icon(
                    if (on) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    null,
                    Modifier.size(18.dp),
                    tint = if (on) c.accent else c.textMuted,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        mode.label,
                        color = if (on) c.textPrimary else c.textSecondary,
                        fontWeight = if (on) FontWeight.W600 else FontWeight.Normal,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(mode.description, color = c.textMuted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
        val intent = StoragePermissions.allFilesAccessIntent(context)
        if (intent != null && !StoragePermissions.hasAllFilesAccess(context)) {
            HorizontalDivider(color = c.divider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { closer.close { runCatching { context.startActivity(intent) } } }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp), tint = c.accent)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Allow all files access", color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.W600)
                    Text("Needed to find WhatsApp/Telegram videos (Hybrid, File scanner)", color = c.textMuted, fontSize = 11.sp)
                }
            }
        }
    }
}
