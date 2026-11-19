package com.ayesha.player.ui.folder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayesha.player.core.theme.AppRadius
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.util.DurationFormatter
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.ui.common.FormatBadge
import com.ayesha.player.ui.common.HeroTarget
import com.ayesha.player.ui.common.NewBadge
import com.ayesha.player.ui.common.TintableCard
import com.ayesha.player.ui.common.VideoThumbnail
import com.ayesha.player.ui.common.sharedVideoThumb
import java.util.Calendar
import kotlin.time.Duration

/** video_card.dart. */
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
    val totalMs = totalDur?.inWholeMilliseconds ?: 0
    val progress = if (savedPos != null && totalMs > 0) (savedPos.inWholeMilliseconds.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val subtitle = when (sortBy) {
        SortOption.DateModified -> {
            val cal = Calendar.getInstance().apply { timeInMillis = video.modifiedMs }
            "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        }
        SortOption.Duration -> totalDur?.let(DurationFormatter::format) ?: video.sizeLabel
        SortOption.Name, SortOption.Size -> video.sizeLabel
    }

    TintableCard(
        onClick = if (selectionMode) onSelectToggle else ({
            // This card's thumbnail is the one that grows into the player.
            HeroTarget.path = video.path
            onClick()
        }),
        onLongClick = if (selectionMode) onSelectToggle else onLongClick,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Column {
            Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                VideoThumbnail(video.path, 88.dp, 58.dp, totalDur, Modifier.sharedVideoThumb(video.path).clip(AppRadius.sm))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        video.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W500, color = c.textPrimary, lineHeight = 18.sp, letterSpacing = (-0.1).sp),
                    )
                    if (isNew) {
                        Spacer(Modifier.height(3.dp))
                        NewBadge()
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormatBadge(video.extensionLabel)
                        Spacer(Modifier.width(8.dp))
                        Text(subtitle, style = AppTheme.text.caption)
                        if (savedPos != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                DurationFormatter.format(savedPos),
                                style = TextStyle(fontSize = 10.sp, color = c.accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600),
                            )
                        }
                    }
                }
                if (!selectionMode) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        "Options",
                        Modifier.clip(AppRadius.sm).clickable(onClick = onLongClick).padding(8.dp).size(20.dp),
                        tint = c.textMuted,
                    )
                }
            }
            if (savedPos != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = c.progressFill,
                    trackColor = c.progressBg,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}
