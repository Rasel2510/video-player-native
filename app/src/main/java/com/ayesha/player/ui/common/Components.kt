package com.ayesha.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayesha.player.core.theme.AppRadius
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.theme.resolveLibraryAccent
import com.ayesha.player.core.util.DurationFormatter
import kotlin.time.Duration

/** centered_prompt.dart: icon in a circle, title, subtitle, action. */
@Composable
fun CenteredPrompt(icon: ImageVector, title: String, subtitle: String, action: @Composable () -> Unit) {
    val c = AppTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).background(c.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = c.textMuted)
            }
            Spacer(Modifier.height(20.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.W600, color = c.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = AppTheme.text.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            action()
        }
    }
}

/** primary_button.dart: accent stadium button. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.accent, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.W600)
    }
}

/** new_badge.dart / new_video_badge.dart: tinted from the chosen "New badge color". */
@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    val color = resolveLibraryAccent(LocalAppearance.current.newBadgeColorIndex, AppTheme.colors.folderIcon)
    NewBadgeChip(color, modifier)
}

@Composable
fun NewBadgeChip(color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        "NEW",
        modifier = modifier
            .background(color.copy(alpha = 0.14f), shape)
            .border(1.dp, color.copy(alpha = 0.3f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.W800, color = color, letterSpacing = 0.8.sp),
    )
}

/** format_badge.dart: "MP4" chip. */
@Composable
fun FormatBadge(ext: String) {
    val c = AppTheme.colors
    Text(
        ext,
        modifier = Modifier.background(c.accentSoft, AppRadius.xs).padding(horizontal = 6.dp, vertical = 2.dp),
        style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.W700, color = c.accent, letterSpacing = 0.5.sp),
    )
}

/** resume_pill.dart: "▶ 12:34" on a folder card. */
@Composable
fun ResumePill(position: Duration, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .clip(AppRadius.xl)
            .background(c.accentSoft)
            .border(1.dp, c.accentGlow, AppRadius.xl)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(13.dp), tint = c.accent)
        Spacer(Modifier.width(4.dp))
        Text(
            DurationFormatter.format(position),
            style = TextStyle(fontSize = 10.sp, color = c.accent, fontWeight = FontWeight.W600, fontFamily = FontFamily.Monospace),
        )
    }
}

/** option_row.dart: icon + label row in the options sheets. Null [onClick] = disabled. */
@Composable
fun OptionRow(icon: ImageVector, label: String, onClick: (() -> Unit)?, color: Color? = null) {
    val c = color ?: if (onClick == null) AppTheme.colors.textMuted else AppTheme.colors.textPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppRadius.sm)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = c)
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 14.sp, color = c)
    }
}

/** Confirm dialog in the app's dialog style; [destructive] paints the confirm button red. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
        shape = AppRadius.md,
        title = { Text(title, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.W600) },
        text = { Text(text, color = c.textSecondary, fontSize = 14.sp) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.W600) }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) c.errorRed else c.accent,
                    contentColor = Color.White,
                ),
            ) { Text(confirmLabel, fontSize = 13.sp, fontWeight = FontWeight.W600) }
        },
    )
}

/**
 * resume_dialog.dart: "Continue watching?". [onResult] gets the saved position (resume),
 * Duration.ZERO (start over); dismissing calls [onDismiss] and opens nothing.
 */
@Composable
fun ResumeDialog(position: Duration, onResult: (Duration) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
        shape = AppRadius.md,
        title = { Text("Continue watching?", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.W600) },
        text = { Text("Paused at ${DurationFormatter.format(position)}", color = c.textSecondary, fontSize = 14.sp) },
        dismissButton = {
            TextButton(onClick = { onResult(Duration.ZERO) }) { Text("Start over", color = c.textSecondary) }
        },
        confirmButton = {
            Button(
                onClick = { onResult(position) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White),
            ) { Text("Resume", fontSize = 13.sp, fontWeight = FontWeight.W600) }
        },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppTheme.text.label, modifier = modifier)
}

/** Horizontal arrangement shared by sheet headers: accent icon + bold title. */
@Composable
fun SheetHeader(icon: ImageVector?, title: String) {
    val c = AppTheme.colors
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = c.accent)
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
    }
}
