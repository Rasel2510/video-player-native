package com.ayesha.player.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import com.ayesha.player.ui.common.rememberPrefetchingListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.theme.ThemeMode
import com.ayesha.player.data.device.StoragePermissions
import com.ayesha.player.data.library.ContinueWatchingItem
import com.ayesha.player.data.prefs.AppearanceSettings
import com.ayesha.player.data.prefs.PlaybackSettings
import com.ayesha.player.ui.common.CenteredPrompt
import com.ayesha.player.ui.common.ConfirmDialog
import com.ayesha.player.ui.common.PrimaryButton
import com.ayesha.player.ui.common.ResumeDialog
import com.ayesha.player.ui.navigation.Route
import kotlinx.coroutines.launch

private enum class HomeSheet { None, Menu, ScanMode }

/**
 * home_screen.dart + library_screen.dart. [onOpenPlayer] / [onOpenFolder] navigate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPlayer: (Route.Player) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenVault: (Route.VaultPin) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val c = AppTheme.colors
    val context = LocalContext.current
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val prompt by vm.opener.prompt.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(HomeSheet.None) }

    LaunchedEffect(Unit) { vm.opener.navigation.collect(onOpenPlayer) }
    LaunchedEffect(Unit) { vm.vaultNav.collect(onOpenVault) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openPicked(uri, context.contentResolver)
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                // Flutter's AppBar: 56 dp tall (kToolbarHeight), 22 dp icons. Compose defaults are 64 / 24.
                expandedHeight = 56.dp,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(c.accent, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text("Videos", fontSize = 16.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.2).sp)
                    }
                },
                actions = {
                    IconButton(onClick = { sheet = HomeSheet.ScanMode }) {
                        Icon(Icons.Rounded.TravelExplore, "Library scan mode", Modifier.size(22.dp), tint = c.textSecondary)
                    }
                    val themeIcon = when ((appearance ?: AppearanceSettings()).themeMode) {
                        ThemeMode.Light -> Icons.Rounded.LightMode
                        ThemeMode.Dark -> Icons.Rounded.DarkMode
                        ThemeMode.System -> Icons.Rounded.BrightnessAuto
                    }
                    IconButton(onClick = vm::toggleTheme) { Icon(themeIcon, "Toggle theme", Modifier.size(22.dp), tint = c.textSecondary) }
                    IconButton(onClick = { sheet = HomeSheet.Menu }) {
                        Icon(Icons.Rounded.MoreVert, "More options", Modifier.size(22.dp), tint = c.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.surface,
                    scrolledContainerColor = c.surface,
                    titleContentColor = c.textPrimary,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            LibraryContent(vm, playback ?: PlaybackSettings(), onOpenFolder)
        }
    }

    when (sheet) {
        HomeSheet.Menu -> MenuSheet(
            appearance = appearance ?: AppearanceSettings(),
            playback = playback ?: PlaybackSettings(),
            vm = vm,
            onOpenFile = { picker.launch(arrayOf("video/*")) },
            onOpenVault = vm::openVault,
            onDismiss = { sheet = HomeSheet.None },
        )
        HomeSheet.ScanMode -> ScanModeSheet(
            selected = (playback ?: PlaybackSettings()).scanMode,
            onSelect = vm::setScanMode,
            onDismiss = { sheet = HomeSheet.None },
        )
        HomeSheet.None -> Unit
    }

    prompt?.let { p -> ResumeDialog(p.position, onResult = vm.opener::choose, onDismiss = vm.opener::dismiss) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(vm: LibraryViewModel, playback: PlaybackSettings, onOpenFolder: (String) -> Unit) {
    val context = LocalContext.current
    val access by vm.access.collectAsStateWithLifecycle()
    // Separate, de-duplicated slices of the library state: a scan-progress tick or one folder's
    // resume point loading must not recompose every visible card (that was the scroll jank).
    val libraryFolders by vm.folders.collectAsStateWithLifecycle()
    val scan by vm.scanInfo.collectAsStateWithLifecycle()
    val newPathsState = vm.newPaths.collectAsStateWithLifecycle()
    val resumesState = vm.folderResumes.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val searchOpen by vm.searchOpen.collectAsStateWithLifecycle()
    val searchText by vm.searchText.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var removeItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }

    // Permission flow (library_screen.dart): media access first; then "All files access" so the
    // filesystem walk can read .nomedia folders. Unlike the Flutter build, the Settings page is
    // opened automatically only once, not on every launch until granted.
    fun applyResult(forceScan: Boolean) {
        if (StoragePermissions.canReadLibrary(context)) vm.onAccessGranted(forceScan) else vm.onAccessDenied()
    }
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val intent = StoragePermissions.allFilesAccessIntent(context)
        if (!StoragePermissions.hasAllFilesAccess(context) && !playback.allFilesAccessAsked && intent != null) {
            vm.markAllFilesAccessAsked()
            vm.awaitingStorageSettings = true
            runCatching { context.startActivity(intent) }.onFailure { vm.awaitingStorageSettings = false }
        }
        applyResult(forceScan = false)
    }
    fun requestAccess() = runtimeLauncher.launch(StoragePermissions.runtime)

    LaunchedEffect(Unit) {
        if (StoragePermissions.canReadLibrary(context)) vm.onAccessGranted(forceScan = false) else requestAccess()
    }
    // The player is drawn over this screen, so closing it isn't a resume: refresh then too.
    com.ayesha.player.ui.player.OnPlayerClosed { vm.onResume() }
    LifecycleResumeEffect(Unit) {
        if (vm.awaitingStorageSettings) {
            vm.awaitingStorageSettings = false
            // Just granted: no valid cache yet, scan now (Flutter's FIX #PERM-SCAN).
            if (StoragePermissions.hasAllFilesAccess(context)) vm.onAccessGranted(forceScan = true)
        } else {
            vm.onResume()
        }
        onPauseOrDispose { }
    }

    when (access) {
        Access.Checking -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppTheme.colors.accent)
            }
            return
        }
        Access.Denied -> {
            CenteredPrompt(Icons.Outlined.LockOpen, "Storage access needed", "Grant permission to scan your device for videos") {
                PrimaryButton("Grant Permission", ::requestAccess)
            }
            return
        }
        Access.Granted -> Unit
    }

    if (scan.isScanning && libraryFolders.isEmpty()) {
        val progress by vm.scanProgress.collectAsStateWithLifecycle()
        ScanningScreen(progress, scan.storageCount)
        return
    }
    // Saved library still loading: stay blank for that instant instead of flashing "No videos found".
    if (!scan.hasLoaded && libraryFolders.isEmpty()) return
    if (!scan.isScanning && libraryFolders.isEmpty()) {
        CenteredPrompt(Icons.Outlined.VideoLibrary, "No videos found", "Pull down to refresh, or tap below to scan") {
            PrimaryButton("Scan now") { vm.refresh(forceScan = true) }
        }
        return
    }

    val multiStorage = scan.storageCount > 1
    val searching = search.query.isNotEmpty()
    val folders = if (searching) search.folders else libraryFolders
    val matched = search.videos
    val showCw = playback.continueWatchingEnabled && !searching && continueWatching.isNotEmpty()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column {
        LibraryHeader(
            folderCount = libraryFolders.size,
            filteredCount = if (searching) search.folders.size else null,
            storageCount = if (multiStorage) scan.storageCount else null,
            isScanning = scan.isScanning,
            fromCache = scan.fromCache,
            searchOpen = searchOpen,
            searchText = searchText,
            onSearchText = vm::onSearchText,
            onToggleSearch = vm::toggleSearch,
            onRescan = if (scan.isScanning) null else ({ vm.refresh(forceScan = true) }),
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    vm.pullToRefresh()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = rememberPrefetchingListState(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp + bottomInset),
            ) {
                if (searching && folders.isEmpty() && matched.isEmpty()) {
                    item { NoFolderResults(search.query) }
                }
                if (showCw) {
                    item(key = "cw") {
                        ContinueWatchingRow(continueWatching, vm::openContinueWatching, onRemove = { removeItem = it })
                    }
                }
                items(folders, key = { it.path }, contentType = { "folder" }) { folder ->
                    LaunchedEffect(folder.path) { vm.ensureFolderResume(folder) }
                    // Per-card derived reads: only the card whose value changed recomposes.
                    val resume by remember(folder.path) { derivedStateOf { resumesState.value[folder.path] } }
                    val isNew by remember(folder.path) { derivedStateOf { folder.path in newPathsState.value } }
                    FolderCard(
                        folder = folder,
                        isExternal = multiStorage && !folder.path.contains("/emulated/"),
                        isNew = isNew,
                        resumePosition = resume?.position,
                        onClick = { onOpenFolder(folder.path) },
                        onResume = resume?.let { r -> { vm.resumeFolder(folder, r) } },
                    )
                }
                if (searching && folders.isNotEmpty() && matched.isNotEmpty()) {
                    item(key = "videos-header") {
                        Text("Videos · ${matched.size}", style = AppTheme.text.label, modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 8.dp))
                    }
                }
                items(matched, key = { "v:" + it.video.path }) { m ->
                    SearchVideoRow(m.video, m.folder.name) { vm.openSearchMatch(m) }
                }
            }
        }
    }

    removeItem?.let { item ->
        ConfirmDialog(
            title = "Remove from Continue Watching?",
            text = "\"${item.video.name}\" will be removed from the row. The video and its resume point are kept.",
            confirmLabel = "Remove",
            onConfirm = {
                vm.removeFromContinueWatching(item)
                removeItem = null
            },
            onDismiss = { removeItem = null },
        )
    }
}
