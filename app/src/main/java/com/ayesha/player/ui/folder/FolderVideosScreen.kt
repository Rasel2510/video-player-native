package com.ayesha.player.ui.folder

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.util.DurationFormatter
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.ui.common.*
import com.ayesha.player.ui.library.SearchField
import com.ayesha.player.ui.navigation.Route
import kotlin.time.Duration

private sealed interface FolderDialog {
    data class Options(val video: VideoFile) : FolderDialog
    data class Details(val video: VideoFile) : FolderDialog
    data class Rename(val video: VideoFile) : FolderDialog
    data class Delete(val video: VideoFile) : FolderDialog
    data object DeleteSelected : FolderDialog
    data object Sort : FolderDialog
    data object SelectionOptions : FolderDialog
}

/**
 * folder_videos_screen.dart. [pickedFolder] is the folder picker's answer (null until one
 * arrives); call [onPickedConsumed] once it's handled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderVideosScreen(
    onBack: () -> Unit,
    onOpenPlayer: (Route.Player) -> Unit,
    onPickFolder: (Route.FolderPicker) -> Unit,
    pickedFolder: String?,
    onPickedConsumed: () -> Unit,
    vm: FolderVideosViewModel = hiltViewModel(),
) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val folder by vm.folder.collectAsStateWithLifecycle()
    val listing by vm.listing.collectAsStateWithLifecycle()
    // Kept as State objects and read per card through derivedStateOf: duration batches (every
    // 150 ms while probing) used to recompose every visible card.
    val positionsState = vm.positions.collectAsStateWithLifecycle()
    val positions by positionsState
    val positionsLoaded by vm.positionsLoaded.collectAsStateWithLifecycle()
    val durationsState = vm.durations.collectAsStateWithLifecycle()
    val durations by durationsState
    val newPathsState = vm.newPaths.collectAsStateWithLifecycle()
    val sortBy by vm.sortBy.collectAsStateWithLifecycle()
    val searchOpen by vm.searchOpen.collectAsStateWithLifecycle()
    val searchText by vm.searchText.collectAsStateWithLifecycle()
    val selectionMode by vm.selectionMode.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val prompt by vm.opener.prompt.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.opener.navigation.collect(onOpenPlayer) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.closeEvents.collect { onBack() } }
    LaunchedEffect(pickedFolder) {
        if (pickedFolder != null) {
            vm.onFolderPicked(pickedFolder)
            onPickedConsumed()
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    // The player is drawn over this screen, so closing it isn't a resume: refresh the resume point then.
    com.ayesha.player.ui.player.OnPlayerClosed { vm.onResume() }

    val f = folder
    val sorted = listing.sorted
    val display = listing.display
    val lastWatched = remember(sorted, positionsLoaded, searchText) {
        derivedStateOf { if (positionsLoaded && searchText.isEmpty()) vm.lastWatched(sorted, positionsState.value) else null }
    }
    val last = lastWatched.value
    val filtered = searchText.trim().isNotEmpty()

    fun pickFolder(move: Boolean) {
        vm.pendingTransferMove = move
        onPickFolder(
            Route.FolderPicker(
                excludePath = vm.folderPath,
                title = if (move) "Move to folder" else "Copy to folder",
                confirmLabel = if (move) "Move here" else "Copy here",
            ),
        )
    }

    fun dismissSearchKeyboard() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // Flutter's AppBar: 56 dp tall (kToolbarHeight), 22 dp icons. Compose defaults are 64 / 24.
                expandedHeight = 56.dp,
                navigationIcon = {
                    IconButton(onClick = if (selectionMode) vm::exitSelection else onBack) {
                        Icon(if (selectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(22.dp), tint = c.textSecondary)
                    }
                },
                title = {
                    if (selectionMode) {
                        Text("${selected.size} selected", fontSize = 16.sp, fontWeight = FontWeight.W600, color = c.textPrimary)
                    } else {
                        Column {
                            Text(f?.name.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.W600, color = c.textPrimary, letterSpacing = (-0.2).sp)
                            val total = sorted.size
                            Text(
                                "${display.size}${if (filtered) " of $total" else ""} video${if (total == 1) "" else "s"} · ${f?.totalSizeLabel.orEmpty()}",
                                style = AppTheme.text.caption,
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        val allSelected = selected.size == display.size
                        IconButton(onClick = { vm.selectAll(display) }) {
                            Icon(if (allSelected) Icons.Rounded.SelectAll else Icons.Rounded.Checklist, if (allSelected) "Deselect all" else "Select all", Modifier.size(22.dp), tint = c.textSecondary)
                        }
                        IconButton(onClick = { ShareHelper.share(context, vm.selectedVideos()) }, enabled = selected.isNotEmpty()) {
                            Icon(Icons.Outlined.Share, "Share", Modifier.size(22.dp), tint = if (selected.isEmpty()) c.textMuted else c.textSecondary)
                        }
                        IconButton(onClick = { vm.moveToVault(vm.selectedVideos()) }, enabled = selected.isNotEmpty()) {
                            Icon(Icons.Rounded.Lock, "Move to Vault", Modifier.size(22.dp), tint = if (selected.isEmpty()) c.textMuted else c.textSecondary)
                        }
                        IconButton(onClick = { dialog = FolderDialog.SelectionOptions }, enabled = selected.isNotEmpty()) {
                            Icon(Icons.Rounded.MoreVert, "More options", Modifier.size(22.dp), tint = if (selected.isEmpty()) c.textMuted else c.textSecondary)
                        }
                    } else {
                        IconButton(onClick = vm::toggleSearch) {
                            Icon(
                                if (searchOpen) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                                if (searchOpen) "Close search" else "Search",
                                Modifier.size(20.dp),
                                tint = if (searchOpen) c.accent else c.textSecondary,
                            )
                        }
                        IconButton(onClick = { dialog = FolderDialog.Sort }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort", Modifier.size(22.dp), tint = c.textSecondary) }
                        IconButton(onClick = { vm.enterSelection() }) { Icon(Icons.Rounded.Checklist, "Select multiple", Modifier.size(22.dp), tint = c.textSecondary) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface, scrolledContainerColor = c.surface),
            )
        },
        floatingActionButton = {
            if (!selectionMode && last != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        dismissSearchKeyboard()
                        vm.resume(last)
                    },
                    containerColor = c.accent,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(50),
                    icon = { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp)) },
                    text = {
                        Column {
                            Text("Resume", fontSize = 13.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.2).sp)
                            Text(
                                DurationFormatter.format(positions[last.path] ?: Duration.ZERO),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(selectionMode, enter = expandVertically(), exit = shrinkVertically()) {
                Surface(color = c.surface) {
                    Column {
                        HorizontalDivider(color = c.divider)
                        Button(
                            onClick = { dialog = FolderDialog.DeleteSelected },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.errorRed,
                                contentColor = Color.White,
                                disabledContainerColor = c.divider,
                                disabledContentColor = c.textMuted,
                            ),
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete (${selected.size})", fontWeight = FontWeight.W600, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            AnimatedVisibility(searchOpen) {
                SearchField(searchText, vm::onSearchText, "Search videos…", Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp))
            }
            if (display.isEmpty()) {
                if (searchOpen && filtered) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.SearchOff, null, Modifier.size(40.dp), tint = c.textMuted)
                            Spacer(Modifier.height(14.dp))
                            Text("No videos match \"${searchText.trim().lowercase()}\"", color = c.textSecondary, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = rememberPrefetchingListState(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = if (selectionMode) 16.dp else if (last != null) 96.dp else 16.dp,
                    ),
                ) {
                    items(display, key = { it.path }, contentType = { "video" }) { v ->
                        val pos by remember(v.path) { derivedStateOf { positionsState.value[v.path]?.takeIf { it.isPositive() } } }
                        val dur by remember(v.path) { derivedStateOf { durationsState.value[v.path] } }
                        val isNew by remember(v.path) { derivedStateOf { v.path in newPathsState.value } }
                        VideoCard(
                            video = v,
                            savedPos = pos,
                            totalDur = dur,
                            isNew = isNew,
                            sortBy = sortBy,
                            selectionMode = selectionMode,
                            isSelected = v.path in selected,
                            onClick = {
                                dismissSearchKeyboard()
                                vm.open(v)
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dialog = FolderDialog.Options(v)
                            },
                            onSelectToggle = { vm.toggleSelected(v) },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        FolderDialog.Sort -> SortSheet(sortBy, vm::setSort) { dialog = null }
        is FolderDialog.Options -> VideoOptionsSheet(
            video = d.video,
            hasResume = vm.hasResume(d.video.path),
            onPlay = {
                dismissSearchKeyboard()
                vm.open(d.video)
            },
            onRename = { dialog = FolderDialog.Rename(d.video) },
            onDetails = { dialog = FolderDialog.Details(d.video) },
            onSelect = { vm.enterSelection(d.video) },
            onCopyPath = {
                ShareHelper.copyToClipboard(context, d.video.path)
                vm.showMessage("Path copied to clipboard")
            },
            onMoveToVault = { vm.moveToVault(listOf(d.video)) },
            onClearResume = { vm.clearResume(listOf(d.video.path)) },
            onDelete = { dialog = FolderDialog.Delete(d.video) },
            onDismiss = { if (dialog == d) dialog = null },
        )
        is FolderDialog.Details -> VideoDetailsSheet(
            d.video,
            durations[d.video.path],
            onCopied = { vm.showMessage("Path copied to clipboard") },
        ) { dialog = null }
        is FolderDialog.Rename -> RenameDialog(
            d.video,
            onRename = {
                vm.rename(d.video, it)
                dialog = null
            },
        ) { dialog = null }
        is FolderDialog.Delete -> ConfirmDialog(
            title = "Delete video?",
            text = "\"${d.video.name}\" will be permanently removed from your device.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vm.delete(listOf(d.video.path))
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        FolderDialog.DeleteSelected -> ConfirmDialog(
            title = "Delete ${selected.size} videos?",
            text = "Selected videos will be permanently removed from your device.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vm.delete(selected)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        FolderDialog.SelectionOptions -> SelectionOptionsSheet(
            selectedCount = selected.size,
            canClearResume = selected.any { vm.hasResume(it) },
            onClearResume = { vm.clearResume(selected) },
            onMoveToAlbum = { pickFolder(move = true) },
            onCopyToAlbum = { pickFolder(move = false) },
        ) { if (dialog == FolderDialog.SelectionOptions) dialog = null }
    }

    prompt?.let { p -> ResumeDialog(p.position, onResult = vm.opener::choose, onDismiss = vm.opener::dismiss) }
}
