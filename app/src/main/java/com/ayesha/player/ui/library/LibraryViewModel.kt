package com.ayesha.player.ui.library

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ayesha.player.core.theme.LibraryCardStyle
import com.ayesha.player.core.theme.PlayerControlsStyle
import com.ayesha.player.data.intent.OpenFileEvents
import com.ayesha.player.data.library.ContinueWatchingItem
import com.ayesha.player.data.library.FolderResume
import com.ayesha.player.data.library.LibraryRepository
import com.ayesha.player.data.library.PlaybackHistory
import com.ayesha.player.data.library.VideoLauncher
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.model.VideoFolder
import com.ayesha.player.data.prefs.AppearancePreferences
import com.ayesha.player.data.prefs.LibraryScanMode
import com.ayesha.player.data.prefs.PlaybackPreferences
import com.ayesha.player.ui.common.OpenVideoFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Access { Checking, Denied, Granted }

/** The header and empty states only need these; kept apart from the folder list on purpose. */
@Immutable
data class ScanInfo(
    val isScanning: Boolean = false,
    val fromCache: Boolean = false,
    val storageCount: Int = 0,
    val hasLoaded: Boolean = false,
)

@Immutable
data class SearchMatch(val video: VideoFile, val folder: VideoFolder)

@Immutable
data class SearchResults(
    val query: String = "",
    val folders: List<VideoFolder> = emptyList(),
    val videos: List<SearchMatch> = emptyList(),
)

/**
 * Backs the home screen and the library list (home_screen.dart + library_screen.dart).
 * The permission prompts themselves run in the composable (they need activity launchers);
 * this holds the outcome.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val history: PlaybackHistory,
    private val appearancePrefs: AppearancePreferences,
    private val playbackPrefs: PlaybackPreferences,
    private val openFileEvents: OpenFileEvents,
    private val vaultSecurity: com.ayesha.player.data.vault.VaultSecurity,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    launcher: VideoLauncher,
) : ViewModel() {

    private val _vaultNav = kotlinx.coroutines.channels.Channel<com.ayesha.player.ui.navigation.Route.VaultPin>(kotlinx.coroutines.channels.Channel.BUFFERED)

    /** Where "Secure Vault" leads: the PIN pad, in setup mode on first use. */
    val vaultNav = _vaultNav.receiveAsFlow()

    fun openVault() {
        viewModelScope.launch {
            val mode = if (vaultSecurity.hasPin()) "unlock" else "create"
            _vaultNav.send(com.ayesha.player.ui.navigation.Route.VaultPin(mode, "open"))
        }
    }

    val state = library.state

    // De-duplicated slices of the library state, so each part of the screen only recomposes for
    // its own changes (the scan-progress counter ticks many times during a walk).
    val folders: StateFlow<List<VideoFolder>> = library.state.map { it.folders }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, library.state.value.folders)
    val newPaths: StateFlow<Set<String>> = library.state.map { it.newPaths }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, library.state.value.newPaths)
    val scanInfo: StateFlow<ScanInfo> = library.state.map { ScanInfo(it.isScanning, it.fromCache, it.storageRoots.size, it.hasLoaded) }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, ScanInfo())
    val scanProgress: StateFlow<Int> = library.state.map { it.scanProgress }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)
    val appearance = appearancePrefs.settings
    val playback = playbackPrefs.settings
    val opener = OpenVideoFlow(viewModelScope, launcher)

    // Decided synchronously, so the first frame never shows a "checking permission" spinner.
    private val _access = MutableStateFlow(
        if (com.ayesha.player.data.device.StoragePermissions.canReadLibrary(appContext)) Access.Granted else Access.Checking,
    )
    val access = _access.asStateFlow()

    /** Set while we've sent the user to the "All files access" settings page. */
    var awaitingStorageSettings = false

    private val _continueWatching = MutableStateFlow<List<ContinueWatchingItem>>(emptyList())
    val continueWatching = _continueWatching.asStateFlow()

    // ── Folder resume pills (Flutter's FIX #OPT-6: loaded per folder as it scrolls into view) ──
    private val _folderResumes = MutableStateFlow<Map<String, FolderResume?>>(emptyMap())
    val folderResumes = _folderResumes.asStateFlow()
    private val requestedResumes = HashSet<String>()

    // ── Search ───────────────────────────────────────────────────────────────
    private val _searchOpen = MutableStateFlow(false)
    val searchOpen = _searchOpen.asStateFlow()
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()

    /** The query actually applied: trails typing by 180 ms so a big library isn't refiltered per key. */
    private val appliedQuery = MutableStateFlow("")
    private var searchDebounce: Job? = null

    val search: StateFlow<SearchResults> = combine(
        library.state.map { it.folders }.distinctUntilChanged(),
        appliedQuery,
    ) { folders, q ->
        if (q.isEmpty()) {
            SearchResults()
        } else {
            SearchResults(
                query = q,
                folders = folders.filter { it.name.lowercase().contains(q) },
                // Across every folder, so a video is found without knowing where it lives.
                videos = folders.flatMap { f ->
                    f.videos.filter { it.name.lowercase().contains(q) }.map { SearchMatch(it, f) }
                },
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    init {
        // "Open with" from other apps: straight to the player (after the resume question).
        viewModelScope.launch {
            openFileEvents.pending.filterNotNull().collect { path ->
                openFileEvents.consume()
                val name = openFileEvents.nameFromPath(path)
                opener.open(VideoFile(path, name, 0, System.currentTimeMillis()), folderPath = null)
            }
        }
    }

    // ── Access ───────────────────────────────────────────────────────────────

    /** [forceScan]: access was just granted, so there's no valid cache to show first. */
    fun onAccessGranted(forceScan: Boolean) {
        _access.value = Access.Granted
        refresh(forceScan)
    }

    fun onAccessDenied() {
        _access.value = Access.Denied
    }

    fun markAllFilesAccessAsked() {
        playbackPrefs.setAllFilesAccessAsked()
    }

    /** On resume: pick up new videos and fresh resume points (back from the player). */
    fun onResume() {
        if (_access.value != Access.Granted) return
        if (!loadedOnce || library.consumeStale()) {
            // First show, or back from the background: files may have changed meanwhile.
            refresh(forceScan = false)
            refreshResumes()
            return
        }
        // Back from another screen (usually the player): only what watching changes, and after
        // the screen transition, so a library reload doesn't compete with it for frames. New
        // and deleted files are picked up live by the MediaStore watch.
        viewModelScope.launch {
            delay(RETURN_REFRESH_DELAY_MS)
            loadContinueWatching()
            refreshResumes()
        }
    }

    /** Refreshes the resume pills in place: clearing them first made every pill blink. */
    private fun refreshResumes() {
        val shown = library.state.value.folders.filter { it.path in requestedResumes }
        if (shown.isEmpty()) return
        viewModelScope.launch {
            val fresh = shown.associate { it.path to history.folderResume(it) }
            _folderResumes.update { it + fresh }
        }
    }

    private var loadedOnce = false

    fun refresh(forceScan: Boolean) {
        loadedOnce = true
        viewModelScope.launch { loadContinueWatching() }
        viewModelScope.launch { library.load(forceScan) }
    }

    /** Pull-to-refresh: suspends until the scan is done, so the indicator stays up meanwhile. */
    suspend fun pullToRefresh() {
        library.load(forceScan = true)
    }

    // ── Continue Watching / folder resume ────────────────────────────────────

    private suspend fun loadContinueWatching() {
        _continueWatching.value = history.continueWatching()
    }

    fun removeFromContinueWatching(item: ContinueWatchingItem) {
        viewModelScope.launch {
            history.removeRecent(item.video.path)
            loadContinueWatching()
        }
    }

    fun ensureFolderResume(folder: VideoFolder) {
        if (!requestedResumes.add(folder.path)) return
        viewModelScope.launch {
            val r = history.folderResume(folder)
            _folderResumes.update { it + (folder.path to r) }
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    fun toggleSearch() {
        searchDebounce?.cancel()
        val open = !_searchOpen.value
        _searchOpen.value = open
        if (!open) {
            _searchText.value = ""
            appliedQuery.value = ""
        }
    }

    fun onSearchText(text: String) {
        _searchText.value = text
        searchDebounce?.cancel()
        searchDebounce = viewModelScope.launch {
            delay(180)
            appliedQuery.value = text.trim().lowercase()
        }
    }

    // ── Opening ──────────────────────────────────────────────────────────────

    fun openPicked(uri: Uri, resolver: ContentResolver) {
        val video = openFileEvents.videoFromUri(uri, resolver)
        opener.open(video, folderPath = null)
    }

    fun openSearchMatch(m: SearchMatch) = opener.open(m.video, m.folder.path)

    fun openContinueWatching(item: ContinueWatchingItem) {
        val folder = library.folder(com.ayesha.player.core.util.PathUtil.parent(item.video.path))
        opener.openAt(item.video, item.position, folder?.path)
    }

    fun resumeFolder(folder: VideoFolder, resume: FolderResume) = opener.openAt(resume.video, resume.position, folder.path)

    // ── Settings ─────────────────────────────────────────────────────────────

    fun toggleTheme() = appearancePrefs.toggleThemeMode()

    fun setScanMode(mode: LibraryScanMode) {
        viewModelScope.launch {
            if (mode == playbackPrefs.current().scanMode) return@launch
            playbackPrefs.setScanMode(mode).join()
            library.rescanForModeChange()
        }
    }

    fun toggleContinueWatching(current: Boolean) = playbackPrefs.setContinueWatchingEnabled(!current)

    fun toggleControlsFrosted(current: PlayerControlsStyle) = appearancePrefs.setControlsStyle(
        if (current == PlayerControlsStyle.Frosted) PlayerControlsStyle.Tint else PlayerControlsStyle.Frosted,
    )

    fun toggleCardTinted(current: LibraryCardStyle) = appearancePrefs.setCardStyle(
        if (current == LibraryCardStyle.Tinted) LibraryCardStyle.Standard else LibraryCardStyle.Tinted,
    )

    fun setFolderIconColor(index: Int) = appearancePrefs.setFolderIconColorIndex(index)
    fun setNewBadgeColor(index: Int) = appearancePrefs.setNewBadgeColorIndex(index)
}

/** Waits out the screen transition (320 ms) before refreshing anything on return. */
private const val RETURN_REFRESH_DELAY_MS = 400L
