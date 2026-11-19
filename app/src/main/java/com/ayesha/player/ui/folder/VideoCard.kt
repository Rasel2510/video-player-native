package com.ayesha.player.ui.folder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayesha.player.core.theme.AppColors
import com.ayesha.player.core.theme.AppRadius
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.util.DurationFormatter
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.ui.common.FormatBadge
import com.ayesha.player.ui.common.NewBadge
import com.ayesha.player.ui.common.TintableCard
import com.ayesha.player.ui.common.VideoThumbnail
import java.util.Calendar
import kotlin.time.Duration

/** Text styles for every card, built once per theme instead of once per row. */
@Immutable
private class CardStyles(c: AppColors, caption: TextStyle) {
    val title = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W500, color = c.textPrimary, lineHeight = 18.sp, letterSpacing = (-0.1).sp)
    val meta = caption
    val position = SpanStyle(fontSize = 10.sp, color = c.accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600)
    val dots = c.textMuted
    val progressFill = c.progressFill
    val progressTrack = c.progressBg
}

/**
 * video_card.dart.
 *
 * Kept flat because it's created for every row scrolled into view: the play glyph, the "⋮"
 * dots and the watch-progress line are drawn rather than built from icon and progress-bar
 * components, the size/date and resume time share one text, and padding replaces spacers.
 * Same look, about half the nodes, no vector painters.
 */
@Composable
fun VideoCard(
    video: VideoFile,
    savedPos: Duration?,
    totalDur: Duration?,
    isNew: Boolean,
    sortBy: SortOption,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit,
) {
    val c = AppTheme.colors
    val caption = AppTheme.text.caption
    val styles = remember(c, caption) { CardStyles(c, caption) }
    val totalMs = totalDur?.inWholeMilliseconds ?: 0
    val progress = if (savedPos != null && totalMs > 0) (savedPos.inWholeMilliseconds.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val meta = remember(video.modifiedMs, video.sizeLabel, sortBy, totalDur, savedPos, styles) {
        metaText(video, sortBy, totalDur, savedPos, styles)
    }

    TintableCard(
        onClick = if (selectionMode) onSelectToggle else onClick,
        onLongClick = if (selectionMode) onSelectToggle else onLongClick,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Row(
            Modifier
                .then(if (progress > 0f) Modifier.progressLine(progress, styles.progressFill, styles.progressTrack) else Modifier)
                // The 2 dp progress line sits under the content, as the bar used to.
                .padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = if (progress > 0f) 14.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Plain `if`: an AnimatedVisibility here built a Transition per card even with selection
            // mode off, which is real work for every row created while scrolling.
            if (selectionMode) {
                Icon(
                    if (isSelected) Icons.Rounded.CheckCircle else Icons.Outlined.Circle,
                    null,
                    Modifier.padding(end = 12.dp).size(22.dp),
                    tint = if (isSelected) c.accent else c.textSecondary,
                )
            }
            VideoThumbnail(video.path, 88.dp, 58.dp, totalDur, Modifier.clip(AppRadius.sm))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = styles.title)
                if (isNew) NewBadge(Modifier.padding(top = 3.dp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FormatBadge(video.extensionLabel)
                    Text(meta, Modifier.padding(start = 8.dp), style = styles.meta, maxLines = 1)
                }
            }
            if (!selectionMode) MoreButton(styles.dots, onLongClick)
        }
    }
}

/** "1.2 GB" / "2024-05-01" / "12:34", then the resume time in the accent color. */
private fun metaText(video: VideoFile, sortBy: SortOption, totalDur: Duration?, savedPos: Duration?, styles: CardStyles): AnnotatedString {
    val subtitle = when (sortBy) {
        SortOption.DateModified -> {
            val cal = Calendar.getInstance().apply { timeInMillis = video.modifiedMs }
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            "${cal.get(Calendar.YEAR)}-${if (m < 10) "0" else ""}$m-${if (d < 10) "0" else ""}$d"
        }
        SortOption.Duration -> totalDur?.let(DurationFormatter::format) ?: video.sizeLabel
        SortOption.Name, SortOption.Size -> video.sizeLabel
    }
    if (savedPos == null) return AnnotatedString(subtitle)
    return buildAnnotatedString {
        append(subtitle)
        // En space + thin space: the 8 dp gap the two texts had.
        append("  ")
        withStyle(styles.position) { append(DurationFormatter.format(savedPos)) }
    }
}

/** The watch-progress line along the card's bottom edge (the card clips its corners). */
private fun Modifier.progressLine(progress: Float, fill: Color, track: Color): Modifier = drawBehind {
    val h = 2.dp.toPx()
    val top = size.height - h
    drawRect(track, topLeft = Offset(0f, top), size = Size(size.width, h))
    drawRect(fill, topLeft = Offset(0f, top), size = Size(size.width * progress, h))
}

/** Icons.Rounded.MoreVert's three dots, drawn: a vector painter per row was the costliest icon. */
@Composable
private fun MoreButton(color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(AppRadius.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Options" }
            .padding(8.dp)
            .size(20.dp)
            .drawBehind {
                // Material's dots: r = 2 at (12, 6/12/18) in a 24-unit box.
                val u = size.width / 24f
                val r = 2f * u
                val x = 12f * u
                drawCircle(color, r, Offset(x, 6f * u))
                drawCircle(color, r, Offset(x, 12f * u))
                drawCircle(color, r, Offset(x, 18f * u))
            },
    )
}
