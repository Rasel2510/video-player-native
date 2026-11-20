package com.ayesha.player.ui.folder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ayesha.player.data.library.FileOperations
import com.ayesha.player.data.library.LibraryRepository
import com.ayesha.player.data.library.PlaybackHistory
import com.ayesha.player.data.library.RenameResult
import com.ayesha.player.data.library.VideoLauncher
import com.ayesha.player.data.media.DurationRepository
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.model.VideoFolder
import com.ayesha.player.data.prefs.PlaybackPreferences
import com.ayesha.player.ui.common.OpenVideoFlow
import com.ayesha.player.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration

/** sort_option.dart. Persisted by ordinal: keep the order. */
enum class SortOption(val label: String, val icon: ImageVector) {
    Name("Name", Icons.Rounded.SortByAlpha),
    DateModified("Date modified", Icons.Rounded.AccessTime),
    Size("File size", Icons.Rounded.DataUsage),
    Duration("Duration", Icons.Rounded.Timer),
}

@Immutable
data class FolderListing(
    val sorted: List<VideoFile> = emptyList(),
    val display: List<VideoFile> = emptyList(),
)

/** Port of _FolderVideosScreenState's logic (folder_videos_screen.dart). */
@HiltViewModel
class FolderVideosViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
    private val history: PlaybackHistory,
    private val durationsRepo: DurationRepository,
    private val fileOps: FileOperations,
    private val prefs: PlaybackPreferences,
    private val vault: com.ayesha.player.data.vault.VaultRepository,
    launcher: VideoLauncher,
) : ViewModel() {

    val folderPath: String = savedState.toRoute<Route.FolderVideos>().folderPath
    val opener = OpenVideoFlow(viewModelScope, launcher)
    val newPaths = library.state.map { it.newPaths }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Live: deletes, renames and new downloads show up without reopening the screen. */
    val folder: StateFlow<VideoFolder?> = library.state.map { s -> s.folders.firstOrNull { it.path == folderPath } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, library.folder(folderPath))

    private val _positions = MutableStateFlow<Map<String, Duration>>(emptyMap())
    val positions = _positions.asStateFlow()
    private val _positionsLoaded = MutableStateFlow(false)
    val positionsLoaded = _positionsLoaded.asStateFlow()
    private val _durations = MutableStateFlow<Map<String, Duration>>(emptyMap())
    val durations = _durations.asStateFlow()

    private val _sortBy = MutableStateFlow(SortOption.Name)
    val sortBy = _sortBy.asStateFlow()

    private val _searchOpen = MutableStateFlow(false)
    val searchOpen = _searchOpen.asStateFlow()
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()
    private val appliedQuery = MutableStateFlow("")
    private var searchJob: Job? = null

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode = _selectionMode.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Fires once the folder has no videos left (all deleted or moved): the screen pops. */
    private val _closeEvents = Channel<Unit>(Channel.CONFLATED)
    val closeEvents = _closeEvents.receiveAsFlow()

    /** Move/copy waiting for the folder picker's answer: true = move. */
    var pendingTransferMove: Boolean? = null

    private var lastOpenedPath: String? = null
    private val loadedPaths = HashSet<String>()
    private var hadVideos = false

    /**
     * Deletes still running. Their rows leave the list before the disk work, so the folder can
     * look empty while a failed delete is about to bring videos back: don't close until it's known.
     */
    private var deletesInFlight = 0

    private fun closeIfEmptied() {
        // The library's current state, not [folder]: that flow can trail it by a dispatch.
        if (hadVideos && deletesInFlight == 0 && library.folder(folderPath)?.videos.isNullOrEmpty()) _closeEvents.trySend(Unit)
    }

    val listing: StateFlow<FolderListing> = combine(folder, _sortBy, _durations, appliedQuery) { f, sort, durs, q ->
        val base = f?.videos.orEmpty()
        val sorted = when (sort) {
            // Already name-sorted by the library: skip a redundant O(n log n) pass.
            SortOption.Name -> base
            SortOption.DateModified -> base.sortedByDescending { it.modifiedMs }
            SortOption.Size -> base.sortedByDescending { it.size }
            SortOption.Duration -> base.sortedByDescending { durs[it.path] ?: Duration.ZERO }
        }
        FolderListing(sorted, if (q.isEmpty()) sorted else sorted.filter { it.name.lowercase().contains(q) })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, FolderListing())

    init {
        viewModelScope.launch {
            _sortBy.value = SortOption.entries.getOrElse(prefs.current().sortByIndex) { SortOption.Name }
        }
        viewModelScope.launch {
            folder.collect { f ->
                if (f == null || f.videos.isEmpty()) {
                    closeIfEmptied()
                    return@collect
                }
                hadVideos = true
                loadMetadata(f.videos.map { it.path }.filter { it !in loadedPaths })
            }
        }
    }

    /**
     * Cached durations first, then resume points (one batched query), then probes for the rest.
     * Probe results are flushed every 150 ms instead of one list update per video.
     */
    private suspend fun loadMetadata(paths: List<String>) {
        if (paths.isEmpty()) return
        loadedPaths += paths
        _durations.update { it + durationsRepo.loadCached(paths) }
        _positions.update { it + history.loadPositions(paths) }
        _positionsLoaded.value = true

        val uncached = paths.filter { it !in _durations.value }
        if (uncached.isEmpty()) return
        val pending = HashMap<String, Duration>()
        var flushJob: Job? = null
        uncached.forEach { path ->
            viewModelScope.launch {
                val d = durationsRepo.probeUncached(path) ?: return@launch
                synchronized(pending) { pending[path] = d }
                if (flushJob?.isActive != true) {
                    flushJob = viewModelScope.launch {
                        delay(150)
                        val batch = synchronized(pending) { HashMap(pending).also { pending.clear() } }
                        _durations.update { it + batch }
                    }
                }
            }
        }
    }

    /** Back from the player: that video's resume point and duration may have changed. */
    fun onResume() {
        val path = lastOpenedPath ?: return
        lastOpenedPath = null
        viewModelScope.launch {
            val pos = history.loadPosition(path)
            _positions.update { if (pos != null) it + (path to pos) else it - path }
            durationsRepo.getDuration(path)?.let { d -> _durations.update { it + (path to d) } }
        }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    fun open(video: VideoFile) {
        lastOpenedPath = video.path
        opener.open(video, folderPath, _durations.value[video.path] ?: video.duration)
    }

    /** The resume button: the newest-modified video that has a resume point. */
    fun lastWatched(sorted: List<VideoFile>, positions: Map<String, Duration>): VideoFile? =
        sorted.filter { (positions[it.path] ?: Duration.ZERO).isPositive() }.maxByOrNull { it.modifiedMs }

    fun resume(video: VideoFile) {
        lastOpenedPath = video.path
        opener.openAt(video, _positions.value[video.path], folderPath)
    }

    // ── Sort / search ────────────────────────────────────────────────────────

    fun setSort(option: SortOption) {
        _sortBy.value = option
        prefs.setSortByIndex(option.ordinal)
    }

    fun toggleSearch() {
        searchJob?.cancel()
        val open = !_searchOpen.value
        _searchOpen.value = open
        if (!open) {
            _searchText.value = ""
            appliedQuery.value = ""
        }
    }

    fun onSearchText(text: String) {
        _searchText.value = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180)
            appliedQuery.value = text.trim().lowercase()
        }
    }

    // ── Selection ────────────────────────────────────────────────────────────

    fun enterSelection(initial: VideoFile? = null) {
        _selectionMode.value = true
        if (initial != null) _selected.update { it + initial.path }
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selected.value = emptySet()
    }

    fun toggleSelected(video: VideoFile) = _selected.update { if (video.path in it) it - video.path else it + video.path }

    fun selectAll(display: List<VideoFile>) {
        val all = display.map { it.path }.toSet()
        _selected.update { if (it.size == all.size) emptySet() else it + all }
    }

    fun selectedVideos(): List<VideoFile> = listing.value.sorted.filter { it.path in _selected.value }

    // ── File actions ─────────────────────────────────────────────────────────

    /** The rows go at once (the library drops them before the disk work); failures come back. */
    fun delete(paths: Collection<String>) {
        val targets = paths.toList() // [paths] may be the selection, cleared just below
        exitSelection()
        deletesInFlight++
        viewModelScope.launch {
            try {
                val gone = fileOps.delete(targets)
                forgetLocally(gone)
                if (gone.size < targets.size) _messages.send("Couldn't delete ${targets.size - gone.size} video(s)")
            } finally {
                deletesInFlight--
                // Failed videos are back in the list by now (finishDelete is applied before delete returns).
                closeIfEmptied()
            }
        }
    }

    fun rename(video: VideoFile, newBaseName: String) {
        viewModelScope.launch {
            when (val r = fileOps.rename(video, newBaseName)) {
                is RenameResult.Renamed -> {
                    _positions.update { m -> m[video.path]?.let { (m - video.path) + (r.video.path to it) } ?: m }
                    _durations.update { m -> m[video.path]?.let { (m - video.path) + (r.video.path to it) } ?: m }
                    loadedPaths += r.video.path
                }
                RenameResult.AlreadyExists -> _messages.send("A file with that name already exists")
                RenameResult.Failed -> _messages.send("Could not rename file")
                RenameResult.Unchanged -> Unit
            }
        }
    }

    /** The folder picker answered: run the move/copy that asked for it. */
    fun onFolderPicked(destination: String) {
        val move = pendingTransferMove ?: return
        pendingTransferMove = null
        val videos = selectedVideos()
        viewModelScope.launch {
            val r = fileOps.transfer(videos, destination, move)
            forgetLocally(r.movedPaths)
            exitSelection()
            val verb = if (move) "Moved" else "Copied"
            val destName = library.folder(destination)?.name ?: destination.substringAfterLast('/')
            _messages.send(
                if (r.skipped == 0) {
                    "$verb ${r.succeeded} video${if (r.succeeded == 1) "" else "s"} to $destName"
                } else {
                    "$verb ${r.succeeded}, skipped ${r.skipped} (already in $destName)"
                },
            )
        }
    }

    fun clearResume(paths: Collection<String>) {
        viewModelScope.launch {
            val withResume = paths.filter { (_positions.value[it] ?: Duration.ZERO).isPositive() }
            if (withResume.isEmpty()) {
                _messages.send("No resume positions to clear")
                return@launch
            }
            fileOps.clearResume(withResume)
            _positions.update { it - withResume.toSet() }
        }
    }

    /** Hides the videos in the Secure Vault (they leave the library right away). */
    fun moveToVault(videos: List<VideoFile>) {
        if (videos.isEmpty()) return
        viewModelScope.launch {
            val n = vault.add(videos)
            forgetLocally(videos.map { it.path })
            exitSelection()
            _messages.send(
                when {
                    n == 0 -> "Couldn't move to the Vault"
                    videos.size == 1 -> "Video moved to Vault"
                    else -> "Videos moved to Vault"
                },
            )
        }
    }

    fun showMessage(text: String) {
        viewModelScope.launch { _messages.send(text) }
    }

    fun hasResume(path: String) = (_positions.value[path] ?: Duration.ZERO).isPositive()

    suspend fun awaitFolder(): VideoFolder = folder.filterNotNull().first()

    private fun forgetLocally(paths: Collection<String>) {
        val set = paths.toSet()
        _positions.update { it - set }
        _durations.update { it - set }
        _selected.update { it - set }
    }
}
