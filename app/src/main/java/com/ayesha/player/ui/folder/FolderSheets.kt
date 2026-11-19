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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayesha.player.core.theme.AppRadius
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.util.DurationFormatter
import com.ayesha.player.data.library.FileNameRules
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.ui.common.AppBottomSheet
import com.ayesha.player.ui.common.OptionRow
import com.ayesha.player.ui.common.ShareHelper
import com.ayesha.player.ui.common.SheetHeader
import java.util.Calendar
import kotlin.time.Duration

/** sort_sheet.dart. */
@Composable
fun SortSheet(current: SortOption, onSelect: (SortOption) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    AppBottomSheet(onDismiss) { closer ->
        Text("SORT BY", style = AppTheme.text.label, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        SortOption.entries.forEach { opt ->
            val on = opt == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { closer.close { onSelect(opt) } }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(opt.icon, null, Modifier.size(20.dp), tint = if (on) c.accent else c.textSecondary)
                Spacer(Modifier.width(14.dp))
                Text(
                    opt.label,
                    Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.W600 else FontWeight.W400,
                    color = if (on) c.accent else c.textPrimary,
                )
                if (on) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = c.accent)
            }
        }
    }
}

/** video_options_sheet.dart. */
@Composable
fun VideoOptionsSheet(
    video: VideoFile,
    hasResume: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onSelect: () -> Unit,
    onCopyPath: () -> Unit,
    onMoveToVault: () -> Unit,
    onClearResume: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    val context = LocalContext.current
    AppBottomSheet(onDismiss) { closer ->
        Text(
            video.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            fontWeight = FontWeight.W600,
            color = c.textPrimary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        HorizontalDivider(color = c.divider)
        Column(Modifier.padding(start = 20.dp, top = 4.dp, end = 20.dp)) {
            OptionRow(Icons.Rounded.PlayArrow, "Play", { closer.close(onPlay) })
            OptionRow(Icons.Rounded.Share, "Share", { closer.close { ShareHelper.share(context, listOf(video)) } })
            OptionRow(Icons.Outlined.Edit, "Rename", { closer.close(onRename) })
            OptionRow(Icons.Rounded.Info, "Details", { closer.close(onDetails) })
            OptionRow(Icons.Rounded.Checklist, "Select", { closer.close(onSelect) })
            OptionRow(Icons.Rounded.ContentCopy, "Copy path", { closer.close(onCopyPath) })
            OptionRow(Icons.Rounded.Lock, "Move to Vault", { closer.close(onMoveToVault) })
            if (hasResume) OptionRow(Icons.Rounded.Replay, "Clear resume position", { closer.close(onClearResume) })
            OptionRow(Icons.Rounded.DeleteOutline, "Delete", { closer.close(onDelete) }, color = c.errorRed)
        }
    }
}

/** selection_options_sheet.dart. */
@Composable
fun SelectionOptionsSheet(
    selectedCount: Int,
    canClearResume: Boolean,
    onClearResume: () -> Unit,
    onMoveToAlbum: () -> Unit,
    onCopyToAlbum: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    AppBottomSheet(onDismiss) { closer ->
        Text(
            "$selectedCount selected",
            fontSize = 14.sp,
            fontWeight = FontWeight.W600,
            color = c.textPrimary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        HorizontalDivider(color = c.divider)
        Column(Modifier.padding(start = 20.dp, top = 4.dp, end = 20.dp)) {
            OptionRow(Icons.Rounded.Replay, "Clear resume position", if (canClearResume) ({ closer.close(onClearResume) }) else null)
            OptionRow(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to album", { closer.close(onMoveToAlbum) })
            OptionRow(Icons.Outlined.CopyAll, "Copy to album", { closer.close(onCopyToAlbum) })
        }
    }
}

/** video_details_sheet.dart. */
@Composable
fun VideoDetailsSheet(video: VideoFile, duration: Duration?, onCopied: () -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val cal = Calendar.getInstance().apply { timeInMillis = video.modifiedMs }
    val date = "%04d-%02d-%02d %02d:%02d".format(
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
    )
    AppBottomSheet(onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, Modifier.size(22.dp), tint = c.accent)
                Spacer(Modifier.width(10.dp))
                Text("Video Details", fontSize = 16.sp, fontWeight = FontWeight.W700, color = c.textPrimary)
            }
            Spacer(Modifier.height(16.dp))
            DetailItem("Name", video.name)
            DetailItem("Format", video.extensionLabel)
            DetailItem("Size", video.sizeLabel)
            DetailItem("Duration", duration?.let(DurationFormatter::format) ?: "Unknown")
            DetailItem("Date Modified", date)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { DetailItem("Path", video.path) }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    ShareHelper.copyToClipboard(context, video.path)
                    onCopied()
                }) { Icon(Icons.Rounded.ContentCopy, "Copy path", Modifier.size(18.dp), tint = c.textSecondary) }
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    val c = AppTheme.colors
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.W600, color = c.textMuted, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.W500, color = c.textPrimary)
    }
}

/** The rename dialog: only the base name is editable; the extension stays fixed. */
@Composable
fun RenameDialog(video: VideoFile, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val (base, ext) = remember(video.name) { FileNameRules.splitName(video.name) }
    var value by remember { mutableStateOf(TextFieldValue(base, TextRange(0, base.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
        shape = AppRadius.md,
        title = { Text("Rename video", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.W600) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                placeholder = { Text("File name") },
                suffix = { Text(ext, color = c.textMuted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(value.text) }),
                shape = AppRadius.sm,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.panel,
                    unfocusedContainerColor = c.panel,
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.border,
                    cursorColor = c.accent,
                    focusedTextColor = c.textPrimary,
                    unfocusedTextColor = c.textPrimary,
                ),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.accent) } },
        confirmButton = {
            Button(
                onClick = { onRename(value.text) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White),
            ) { Text("Rename") }
        },
    )
}
