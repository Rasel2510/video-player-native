package com.ayesha.player.data.library

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.ayesha.player.core.util.Background
import com.ayesha.player.data.db.LibraryDao
import com.ayesha.player.data.db.LibraryVideoEntity
import com.ayesha.player.data.db.SeenPathDao
import com.ayesha.player.data.db.SeenPathEntity
import com.ayesha.player.data.media.DurationRepository
import com.ayesha.player.data.media.FolderScanner
import com.ayesha.player.data.media.LibraryGrouping
import com.ayesha.player.data.media.MediaStoreSource
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.model.VideoFolder
import com.ayesha.player.data.prefs.LibraryScanMode
import com.ayesha.player.data.prefs.PlaybackPreferences
import com.ayesha.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class LibraryState(
    val folders: List<VideoFolder> = emptyList(),
    val isScanning: Boolean = false,
    val scanProgress: Int = 0,
    /** Showing the cached library from the last launch; a refresh is pending or running. */
    val fromCache: Boolean = false,
    val storageRoots: List<String> = emptyList(),
    /** Folder and video paths that get a "NEW" badge. */
    val newPaths: Set<String> = emptySet(),
    /**
     * Something has been shown or decided (cache, scan result, or "nothing there"). Until then
     * the screen stays blank: never "No videos found" while the saved library is still loading.
     */
    val hasLoaded: Boolean = false,
)

/**
 * Port of FoldersNotifier (folders_provider.dart).
 *
 * Every state change runs on [serial], a one-thread dispatcher, so it interleaves only at
 * suspension points: the same model as Dart's single event loop, which the original guards
 * (`_refreshing`, `_refreshPending`, the isScanning early return) rely on. Disk and database
 * work hops to Dispatchers.IO.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class LibraryRepository @Inject constructor(
    private val mediaStore: MediaStoreSource,
    private val scanner: FolderScanner,
    private val libraryDao: LibraryDao,
    private val seenDao: SeenPathDao,
    private val store: DataStore<Preferences>,
    private val durations: DurationRepository,
    private val prefs: PlaybackPreferences,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val s get() = _state.value

    // Everything below is touched only on [serial].
    /** Paths whose badge was dismissed this session (Flutter's FIX #2). */
    private val sessionSeen = HashSet<String>()
    private var watchJob: Job? = null
    private var refreshing = false
    private var refreshPending = false
    private var scanMode = LibraryScanMode.Hybrid
    private var lastFilesystemScanMs: Long? = null

    // Tombstones. MediaStore can lag behind a file operation (older Android, SD cards), and one
    // refresh in that gap would bring a deleted video back, so removed paths are remembered.
    /** Delete still running: hidden even though the file is still there. Value = entry to put back if it fails. */
    private val pendingDeletes = HashMap<String, VideoFile>()

    /**
     * Deleted, moved or vaulted: hidden while the file stays gone. Dropped once MediaStore
     * forgets the path, or the file is back (downloaded again under the same name).
     */
    private val removed = HashSet<String>()

    /** Set when the app goes to the background; the next library screen resume reloads once. */
    @Volatile private var staleAfterBackground = false

    fun markStale() {
        staleAfterBackground = true
    }

    fun consumeStale(): Boolean = staleAfterBackground.also { staleAfterBackground = false }

    // ── Public API ───────────────────────────────────────────────────────────

    suspend fun load(forceScan: Boolean = false): Unit = withContext(serial) {
        if (s.isScanning) return@withContext
        scanMode = prefs.current().scanMode

        // Hybrid / MediaStore: the fast index plus a live observer. Hybrid also walks the
        // filesystem for .nomedia folders (WhatsApp, Telegram) that MediaStore skips.
        if (scanMode != LibraryScanMode.FileScanner) {
            ensureMediaStoreWatch()
            if (!forceScan && s.folders.isEmpty()) {
                val cached = loadCache()
                if (cached.isNotEmpty()) showCache(cached, roots())
            }
            mediaStoreRefresh(
                showScanning = s.folders.isEmpty(),
                withFilesystemMerge = scanMode == LibraryScanMode.Hybrid,
                forceFilesystem = forceScan,
            )
            return@withContext
        }

        // File-scanner mode: recursive walk only.
        stopMediaStoreWatch()
        val roots = roots()
        when {
            forceScan -> fullScan(roots)
            s.folders.isEmpty() -> {
                val cached = loadCache()
                if (cached.isNotEmpty()) {
                    showCache(cached, roots)
                    scope.launch(serial) { backgroundCheck(roots) }
                } else {
                    fullScan(roots)
                }
            }
            else -> scope.launch(serial) { backgroundCheck(roots) }
        }
    }

    /** Re-scans from scratch after the user changes the scan mode. */
    suspend fun rescanForModeChange() {
        withContext(serial) {
            stopMediaStoreWatch()
            _state.value = LibraryState()
        }
        load(forceScan = true)
    }

    /** The user opened [videoPath]: clear its badge, and its folder's once nothing in it is new. */
    fun markSeen(videoPath: String) {
        scope.launch(serial) {
            if (videoPath !in s.newPaths) return@launch
            val updated = s.newPaths.toHashSet().apply { remove(videoPath) }
            sessionSeen += videoPath
            var clearedFolder: String? = null
            val folder = s.folders.firstOrNull { f -> f.videos.any { it.path == videoPath } }
            if (folder != null && folder.videos.none { it.path in updated }) {
                updated -= folder.path
                sessionSeen += folder.path
                clearedFolder = folder.path
            }
            _state.update { it.copy(newPaths = updated) }
            // Persist the folder too (Flutter's FIX #1), or it would re-badge on the next scan.
            persistSeen(listOfNotNull(videoPath, clearedFolder))
        }
    }

    /**
     * Drops videos without a rescan and updates the cache. [deleting]: the files are about to be
     * deleted rather than already gone, so the rows vanish before the disk work starts; call
     * [finishDelete] once it's done.
     */
    fun removeVideos(videoPaths: Collection<String>, deleting: Boolean = false) {
        val gone = videoPaths.toHashSet()
        if (gone.isEmpty()) return
        scope.launch(serial) {
            if (deleting) {
                s.folders.forEach { f -> f.videos.forEach { if (it.path in gone) pendingDeletes[it.path] = it } }
            } else {
                removed += gone
            }
            val folders = s.folders
                .map { f -> VideoFolder(f.path, f.videos.filterNot { it.path in gone }) }
                .filter { it.videos.isNotEmpty() }
            val newPaths = LibraryGrouping.pruneNewPathsForRemovedVideos(s.newPaths, folders, gone)
            _state.update { it.copy(folders = folders, newPaths = newPaths) }
            saveCache(folders)
        }
    }

    fun removeVideo(videoPath: String) = removeVideos(listOf(videoPath))

    /** The delete begun by removeVideos(deleting = true) is over; [failed] videos come back. */
    fun finishDelete(gone: Collection<String>, failed: Collection<String>) {
        scope.launch(serial) {
            gone.forEach {
                pendingDeletes.remove(it)
                removed += it
            }
            val restore = failed.mapNotNull { pendingDeletes.remove(it) }
            if (restore.isNotEmpty()) insertNow(restore)
        }
    }

    /** Swaps in a renamed file without a rescan. */
    fun renameVideo(oldPath: String, renamed: VideoFile) {
        scope.launch(serial) {
            val folders = s.folders.map { f ->
                val i = f.videos.indexOfFirst { it.path == oldPath }
                if (i < 0) f else VideoFolder(f.path, f.videos.toMutableList().also { it[i] = renamed })
            }
            _state.update { it.copy(folders = folders, newPaths = it.newPaths - oldPath) }
            saveCache(folders)
        }
    }

    /**
     * Adds videos straight into the library (restored from the vault, moved or copied here), so
     * they show up at once instead of after a full storage rescan.
     */
    fun insertVideos(videos: Collection<VideoFile>) {
        if (videos.isEmpty()) return
        scope.launch(serial) { insertNow(videos) }
    }

    private suspend fun insertNow(videos: Collection<VideoFile>) {
        val all = LinkedHashMap<String, VideoFile>()
        s.folders.forEach { f -> f.videos.forEach { all[it.path] = it } }
        videos.forEach {
            all[it.path] = it
            pendingDeletes.remove(it.path)
            removed.remove(it.path)
        }
        val folders = LibraryGrouping.groupByFolder(all.values)
        _state.update { it.copy(folders = folders) }
        saveCache(folders)
    }

    /**
     * Shows the saved library from the last launch as early as possible (called from the
     * activity while the splash is up), so the first frame already has folders, as in Flutter.
     */
    fun warmUp() {
        scope.launch(serial) {
            if (s.folders.isNotEmpty()) return@launch
            val cached = loadCache()
            if (cached.isNotEmpty() && s.folders.isEmpty()) showCache(cached, roots())
        }
    }

    fun folder(path: String): VideoFolder? = s.folders.firstOrNull { it.path == path }

    // ── MediaStore ───────────────────────────────────────────────────────────

    private fun ensureMediaStoreWatch() {
        if (watchJob != null) return
        watchJob = scope.launch(serial) {
            // One download/delete fires several callbacks: debounce them (600 ms, as in Dart).
            mediaStore.changes().debounce(600).collect {
                // Pure file-scanner results must not be overwritten by MediaStore.
                if (scanMode != LibraryScanMode.FileScanner) mediaStoreRefresh(showScanning = false)
            }
        }
    }

    private fun stopMediaStoreWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    private suspend fun mediaStoreRefresh(
        showScanning: Boolean,
        withFilesystemMerge: Boolean = false,
        forceFilesystem: Boolean = false,
    ) {
        if (refreshing) {
            refreshPending = true
            return
        }
        refreshing = true
        try {
            val wasEmpty = s.folders.isEmpty()
            if (showScanning && wasEmpty) {
                val roots = roots()
                _state.update { it.copy(isScanning = true, scanProgress = 0, fromCache = false, storageRoots = roots) }
            }

            val videos = mediaStore.queryVideos()
            pruneTombstones(videos)
            // MediaStore already knows each indexed video's duration: no probe needed for those.
            durations.seed(videos)
            val mediaByPath = LinkedHashMap<String, VideoFile>().apply { videos.forEach { put(it.path, it) } }

            // Hybrid keeps the filesystem-only videos already shown (WhatsApp etc.), so a
            // MediaStore-only refresh never makes them vanish; _filesystemMerge rebuilds the
            // authoritative set. MediaStore-only mode trusts MediaStore alone.
            val combined = LinkedHashMap(mediaByPath)
            if (scanMode == LibraryScanMode.Hybrid) {
                s.folders.forEach { f -> f.videos.forEach { combined.putIfAbsent(it.path, it) } }
            }
            applyFoldersIfChanged(LibraryGrouping.groupByFolder(combined.values))

            if (withFilesystemMerge) filesystemMerge(mediaByPath, force = forceFilesystem || wasEmpty)
        } finally {
            refreshing = false
            if (refreshPending) {
                refreshPending = false
                mediaStoreRefresh(showScanning = false)
            }
        }
    }

    /**
     * Walks the filesystem (which, unlike MediaStore, sees .nomedia folders) and merges in what
     * MediaStore missed. Starts from the MediaStore results rather than the current state, so a
     * deleted WhatsApp file is dropped instead of lingering.
     */
    private suspend fun filesystemMerge(mediaByPath: Map<String, VideoFile>, force: Boolean) {
        if (!force && throttled()) return
        lastFilesystemScanMs = SystemClock.elapsedRealtime()
        if (!s.isScanning) _state.update { it.copy(isScanning = true) }

        val combined = LinkedHashMap(mediaByPath)
        val progress = AtomicInteger(0)
        for (root in roots()) {
            val folders = withContext(Background.media) {
                scanner.scanFolders(root) {
                    val p = progress.incrementAndGet()
                    _state.update { st -> st.copy(scanProgress = p) }
                }
            }
            folders.forEach { f -> f.videos.forEach { combined.putIfAbsent(it.path, it) } }
        }
        applyFoldersIfChanged(LibraryGrouping.groupByFolder(combined.values))
    }

    // ── File scanner ─────────────────────────────────────────────────────────

    private suspend fun backgroundCheck(roots: List<String>) {
        // The change check walks the tree itself, so it's throttled like the full walk.
        if (throttled()) return
        lastFilesystemScanMs = SystemClock.elapsedRealtime()
        val changed = withContext(Background.media) {
            val snapshot = runCatching { libraryDao.folderCounts().associate { it.folder to it.count } }
                .getOrDefault(emptyMap())
            scanner.hasChanged(roots, snapshot)
        }
        if (changed) fullScan(roots)
    }

    private suspend fun fullScan(roots: List<String>) {
        lastFilesystemScanMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(isScanning = true, scanProgress = 0, fromCache = false, storageRoots = roots) }

        val folderMap = LinkedHashMap<String, MutableList<VideoFile>>()
        val progress = AtomicInteger(0)
        for (root in roots) {
            val folders = withContext(Background.media) {
                scanner.scanFolders(root) {
                    val p = progress.incrementAndGet()
                    _state.update { st -> st.copy(scanProgress = p) }
                }
            }
            folders.forEach { f -> folderMap.getOrPut(f.path) { mutableListOf() } += f.videos }
            // Save after each root, so a kill mid-scan doesn't leave a blank library next time
            // (Flutter's FIX #SCAN-KILL). Awaited, so it can't land after the final save.
            if (folderMap.isNotEmpty()) saveCache(folderMap.map { (p, v) -> VideoFolder(p, v.toList()) })
        }
        applyFolders(LibraryGrouping.sortedFolders(folderMap))
    }

    // ── Commit ───────────────────────────────────────────────────────────────

    /** Commits only when the set of videos changed, so a no-op rescan doesn't flicker or rewrite. */
    private suspend fun applyFoldersIfChanged(merged: List<VideoFolder>) {
        val visible = withoutRemoved(merged)
        if (LibraryGrouping.sameVideoSet(s.folders, visible)) {
            _state.update { it.copy(isScanning = false, fromCache = false, hasLoaded = true) }
            return
        }
        applyFolders(visible)
    }

    private suspend fun applyFolders(merged: List<VideoFolder>) {
        val seen = loadSeen()
        // Filtered after the suspension: a delete may have started while the seen set loaded.
        val folders = withoutRemoved(merged)
        val badges = LibraryGrouping.computeBadges(folders, seen, sessionSeen)
        _state.update {
            it.copy(folders = folders, isScanning = false, fromCache = false, newPaths = badges.newPaths, hasLoaded = true)
        }
        saveSeen(badges.seenToPersist)
        saveCache(folders)
    }

    // ── Tombstones ───────────────────────────────────────────────────────────

    private fun isTombstoned(path: String) = path in pendingDeletes || path in removed

    private fun isRemoved(path: String) = path in pendingDeletes || (path in removed && !File(path).exists())

    /** [folders] minus the videos the user just deleted, moved or vaulted. */
    private fun withoutRemoved(folders: List<VideoFolder>): List<VideoFolder> {
        if (pendingDeletes.isEmpty() && removed.isEmpty()) return folders
        return folders.mapNotNull { f ->
            if (f.videos.none { isTombstoned(it.path) }) return@mapNotNull f
            val kept = f.videos.filterNot { isRemoved(it.path) }
            if (kept.isEmpty()) null else VideoFolder(f.path, kept)
        }
    }

    /** Forgets finished removals that MediaStore has caught up with, and paths whose file is back. */
    private fun pruneTombstones(indexed: List<VideoFile>) {
        if (removed.isEmpty()) return
        val listed = indexed.asSequence().map { it.path }.filter { it in removed }.toHashSet()
        removed.removeAll { it !in listed || File(it).exists() }
    }

    private suspend fun showCache(cached: List<VideoFolder>, roots: List<String>) {
        // A removed SD card's folders shouldn't show up (Flutter's FIX #SDCARD).
        val valid = cached.filter { f -> roots.any { f.path.startsWith(it) } }
        val newPaths = LibraryGrouping.cachedBadges(valid, loadSeen(), sessionSeen)
        _state.update { it.copy(folders = valid, fromCache = true, storageRoots = roots, newPaths = newPaths, hasLoaded = true) }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private suspend fun roots(): List<String> = withContext(Dispatchers.IO) { scanner.discoverStorageRoots() }

    private fun throttled(): Boolean {
        val last = lastFilesystemScanMs ?: return false
        return SystemClock.elapsedRealtime() - last < MIN_RESCAN_GAP_MS
    }

    private suspend fun loadCache(): List<VideoFolder> = withContext(Dispatchers.IO) {
        runCatching {
            LibraryGrouping.sortedFolders(libraryDao.all().groupBy({ it.folder }, { it.toVideoFile() }))
        }.getOrDefault(emptyList())
    }

    private suspend fun saveCache(folders: List<VideoFolder>) = withContext(Dispatchers.IO) {
        runCatching {
            libraryDao.replaceAll(
                folders.flatMap { f ->
                    f.videos.map { v -> LibraryVideoEntity(v.path, f.path, v.name, v.size, v.modifiedMs) }
                },
            )
        }
        Unit
    }

    /** Null until the first commit: a fresh install shows no badges at all. */
    private suspend fun loadSeen(): Set<String>? = withContext(Dispatchers.IO) {
        runCatching {
            if (store.data.first()[SEEN_INIT_KEY] != true) null else seenDao.all().toHashSet()
        }.getOrNull()
    }

    private suspend fun saveSeen(paths: Set<String>) = withContext(Dispatchers.IO) {
        runCatching {
            // Flag first (Flutter's FIX #3): a kill between the writes then reads as an empty
            // seen set (safe) rather than "never initialised" (which would badge everything).
            store.edit { it[SEEN_INIT_KEY] = true }
            seenDao.replaceAll(paths)
        }
        Unit
    }

    private suspend fun persistSeen(paths: List<String>) = withContext(Dispatchers.IO) {
        runCatching {
            if (store.data.first()[SEEN_INIT_KEY] == true) seenDao.insertAll(paths.map(::SeenPathEntity))
        }
        Unit
    }

    private companion object {
        /** Battery: don't re-walk storage when the app is resumed within 2 minutes of a walk. */
        const val MIN_RESCAN_GAP_MS = 2 * 60 * 1000L
        val SEEN_INIT_KEY = booleanPreferencesKey("folder_scan_seen_paths_init_v1")
    }
}
