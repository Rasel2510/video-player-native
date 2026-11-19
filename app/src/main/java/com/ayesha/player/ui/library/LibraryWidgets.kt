package com.ayesha.player.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayesha.player.core.theme.AppRadius
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.theme.resolveLibraryAccent
import com.ayesha.player.core.util.DurationFormatter
import com.ayesha.player.data.library.ContinueWatchingItem
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.model.VideoFolder
import com.ayesha.player.ui.common.FormatBadge
import com.ayesha.player.ui.common.LocalAppearance
import com.ayesha.player.ui.common.NewBadge
import com.ayesha.player.ui.common.ResumePill
import com.ayesha.player.ui.common.TintableCard
import com.ayesha.player.ui.common.VideoThumbnail
import kotlin.time.Duration

private val SdColor = Color(0xFF40AAAA)
private val SdChipBg = Color(0xFF0A2020)

/** library_header.dart: folder count, scanning spinner / "cached", search toggle, Rescan, search field. */
@Composable
fun LibraryHeader(
    folderCount: Int,
    filteredCount: Int?,
    storageCount: Int?,
    isScanning: Boolean,
    fromCache: Boolean,
    searchOpen: Boolean,
    searchText: String,
    onSearchText: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onRescan: (() -> Unit)?,
) {
    val c = AppTheme.colors
    val t = AppTheme.text
    val plural = if (folderCount == 1) "" else "s"
    Column {
        Row(Modifier.padding(start = 20.dp, top = 6.dp, end = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (filteredCount != null) "$filteredCount of $folderCount folder$plural" else "$folderCount folder$plural",
                style = t.label,
            )
            if (storageCount != null) {
                Spacer(Modifier.width(8.dp))
                Text("· $storageCount storages", style = t.caption)
            }
            Spacer(Modifier.weight(1f))
            if (isScanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.accent)
            if (fromCache && !isScanning) Text("cached", style = t.caption)
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (searchOpen) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search folders",
                    modifier = Modifier.size(20.dp),
                    tint = if (searchOpen) c.accent else c.textSecondary,
                )
            }
            TextButton(onClick = { onRescan?.invoke() }, enabled = onRescan != null) {
                Text("Rescan", fontSize = 12.sp, color = if (isScanning) c.textMuted else c.textSecondary)
            }
        }
        AnimatedVisibility(searchOpen) {
            SearchField(searchText, onSearchText, "Search folders…", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
    }
}

/** The app's search field: filled panel, rounded border, clear button, focused on open. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        textStyle = TextStyle(color = c.textPrimary, fontSize = 14.sp),
        placeholder = { Text(hint, color = c.textMuted, fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = c.textMuted) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Clear, "Clear", Modifier.size(16.dp), tint = c.textMuted)
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = AppRadius.sm,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.panel,
            unfocusedContainerColor = c.panel,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.border,
            cursorColor = c.accent,
        ),
    )
}

/** folder_card.dart. [selected] is used by the folder picker. */
@Composable
fun FolderCard(
    folder: VideoFolder,
    isExternal: Boolean,
    isNew: Boolean,
    resumePosition: Duration?,
    onClick: () -> Unit,
    onResume: (() -> Unit)?,
    selected: Boolean = false,
) {
    val c = AppTheme.colors
    val iconColor = resolveLibraryAccent(LocalAppearance.current.folderIconColorIndex, c.folderIcon)
    TintableCard(onClick = onClick, modifier = Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Icon(Icons.Rounded.Folder, null, Modifier.size(32.dp), tint = if (isExternal) SdColor else iconColor)
                if (isExternal) {
                    Icon(Icons.Rounded.SdCard, null, Modifier.size(10.dp).align(Alignment.BottomEnd), tint = SdColor)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    folder.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W500,
                    color = c.textPrimary,
                    letterSpacing = (-0.2).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isNew) {
                    Spacer(Modifier.height(2.dp))
                    NewBadge()
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val n = folder.videoCount
                    Text("$n video${if (n == 1) "" else "s"}", style = AppTheme.text.bodySmall)
                    if (isExternal) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "SD",
                            modifier = Modifier.background(SdChipBg, AppRadius.xs).padding(horizontal = 6.dp, vertical = 2.dp),
                            style = TextStyle(fontSize = 9.sp, color = SdColor, fontWeight = FontWeight.W700, letterSpacing = 0.5.sp),
                        )
                    }
                }
            }
            if (onResume != null && resumePosition != null) {
                ResumePill(resumePosition, onResume)
                Spacer(Modifier.width(10.dp))
            }
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.size(20.dp), tint = c.accent)
            } else {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = c.textMuted)
            }
        }
    }
}

/** continue_watching_row.dart: horizontal strip; tap resumes directly, long-press removes. */
@Composable
fun ContinueWatchingRow(
    items: List<ContinueWatchingItem>,
    onClick: (ContinueWatchingItem) -> Unit,
    onRemove: (ContinueWatchingItem) -> Unit,
) {
    Column {
        Text("Continue Watching", style = AppTheme.text.label, modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp))
        LazyRow(Modifier.height(138.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.video.path }) { item -> ContinueWatchingCard(item, { onClick(item) }, { onRemove(item) }) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingCard(item: ContinueWatchingItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = AppTheme.colors
    val total = item.duration?.inWholeMilliseconds ?: 0
    val progress = if (total > 0) (item.position.inWholeMilliseconds.toFloat() / total).coerceIn(0f, 1f) else 0f
    val dur = item.duration
    val caption = if (dur != null && dur > item.position) {
        "${DurationFormatter.format(dur - item.position)} left"
    } else {
        DurationFormatter.format(item.position)
    }
    Column(
        Modifier
            .width(148.dp)
            .clip(AppRadius.sm)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.clip(AppRadius.sm)) {
            VideoThumbnail(item.video.path, 148.dp, 83.dp, item.duration)
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp),
                    color = c.progressFill,
                    trackColor = c.progressBg,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.video.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W500, color = c.textPrimary, lineHeight = 15.sp, letterSpacing = (-0.1).sp),
        )
        Spacer(Modifier.height(3.dp))
        Text(caption, style = AppTheme.text.caption)
    }
}

/** search_video_row.dart: a video matched by name anywhere in the library. */
@Composable
fun SearchVideoRow(video: VideoFile, folderName: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .clip(AppRadius.md)
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumbnail(video.path, 72.dp, 48.dp, video.duration, Modifier.clip(AppRadius.sm))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(video.name, fontSize = 13.sp, fontWeight = FontWeight.W500, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatBadge(video.extensionLabel)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(11.dp), tint = c.textMuted)
                Spacer(Modifier.width(3.dp))
                Text(folderName, style = AppTheme.text.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = c.textMuted)
    }
}

/** scanning_screen.dart. */
@Composable
fun ScanningScreen(progress: Int, storageCount: Int) {
    val t = AppTheme.text
    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 2.dp, color = AppTheme.colors.accent)
            Spacer(Modifier.height(24.dp))
            Text("Scanning device…", style = t.body.copy(color = AppTheme.colors.textSecondary))
            Spacer(Modifier.height(6.dp))
            Text("$progress videos found", style = t.caption)
            if (storageCount > 1) {
                Spacer(Modifier.height(4.dp))
                Text("$storageCount storages", style = t.caption)
            }
        }
    }
}

/** no_results.dart (library). */
@Composable
fun NoFolderResults(query: String) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.SearchOff, null, Modifier.size(40.dp), tint = c.textMuted)
        Spacer(Modifier.height(14.dp))
        Text("No folders match \"$query\"", color = c.textSecondary, fontSize = 14.sp)
    }
}
