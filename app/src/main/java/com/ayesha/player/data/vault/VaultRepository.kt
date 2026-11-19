package com.ayesha.player.data.vault

import android.content.Context
import android.media.MediaScannerConnection
import com.ayesha.player.core.util.PathUtil
import com.ayesha.player.data.db.VaultDao
import com.ayesha.player.data.db.VaultEntryEntity
import com.ayesha.player.data.library.LibraryRepository
import com.ayesha.player.data.model.VideoFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port of vault_service.dart + vault_provider.dart. Vaulted files live in filesDir/vault
 * (path_provider's getApplicationSupportDirectory()/vault in the Flutter build) as
 * vault_<micros>_<hash>.vault, so the gallery and other apps can't see them. Their original
 * path is kept so a restore puts them back exactly where they were; resume points therefore
 * survive the round trip. Moves run at most 2 at a time: a cross-volume move is a full copy.
 */
@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: VaultDao,
    private val library: LibraryRepository,
) {
    private val dir: File by lazy { File(context.filesDir, "vault").apply { mkdirs() } }
    private val slots = Semaphore(2)
    private val loadLock = Mutex()

    private val _videos = MutableStateFlow<List<VideoFile>>(emptyList())
    val videos: StateFlow<List<VideoFile>> = _videos.asStateFlow()

    /** Re-reads the vault, dropping entries whose file has disappeared. */
    suspend fun load() = loadLock.withLock {
        withContext(Dispatchers.IO) {
            val entries = dao.all()
            val gone = ArrayList<String>()
            val list = entries.mapNotNull { e ->
                val f = File(e.vaultPath)
                if (!f.exists()) {
                    gone += e.vaultPath
                    null
                } else {
                    VideoFile(e.vaultPath, PathUtil.basename(e.originalPath), f.length(), f.lastModified())
                }
            }
            if (gone.isNotEmpty()) dao.deleteAll(gone)
            _videos.value = list.sortedBy { it.name.lowercase() }
        }
    }

    /** Moves videos into the vault; returns how many made it. The library drops them at once. */
    suspend fun add(videos: List<VideoFile>): Int {
        val moved = withContext(Dispatchers.IO) {
            coroutineScope {
                videos.map { v -> async { slots.withPermit { moveIn(v) } } }.awaitAll().filterNotNull()
            }
        }
        if (moved.isNotEmpty()) library.removeVideos(moved)
        load()
        return moved.size
    }

    private suspend fun moveIn(v: VideoFile): String? {
        val src = File(v.path)
        if (!src.exists()) return null
        val target = File(dir, "vault_${System.nanoTime() / 1000}_${v.path.hashCode()}.vault")
        return try {
            safeMove(src, target)
            dao.upsert(VaultEntryEntity(target.path, v.path))
            v.path
        } catch (_: Exception) {
            null
        }
    }

    /** Puts videos back at their original paths; returns how many. The library then rescans. */
    suspend fun restore(videos: List<VideoFile>): Int {
        val restored = withContext(Dispatchers.IO) {
            coroutineScope {
                videos.map { v -> async { slots.withPermit { restoreOne(v) } } }.awaitAll().filterNotNull()
            }
        }
        load()
        if (restored.isNotEmpty()) {
            // Straight back into the library (no full rescan), and into MediaStore for other apps.
            library.insertVideos(restored.mapNotNull { VideoFile.fromFile(File(it)) })
            runCatching { MediaScannerConnection.scanFile(context, restored.toTypedArray(), null, null) }
        }
        return restored.size
    }

    private suspend fun restoreOne(v: VideoFile): String? {
        val file = File(v.path)
        if (!file.exists()) {
            dao.deleteAll(listOf(v.path))
            return null
        }
        val original = dao.originalFor(v.path) ?: return null
        return try {
            val target = File(original)
            target.parentFile?.mkdirs()
            // Never overwrite a file that has since appeared at the original path.
            if (target.exists()) throw IOException("exists: $original")
            safeMove(file, target)
            dao.deleteAll(listOf(v.path))
            original
        } catch (_: Exception) {
            null
        }
    }

    /** Permanent. */
    suspend fun delete(videos: List<VideoFile>) {
        withContext(Dispatchers.IO) {
            val done = videos.filter { v ->
                val f = File(v.path)
                !f.exists() || f.delete()
            }.map { it.path }
            if (done.isNotEmpty()) dao.deleteAll(done)
        }
        load()
    }

    /** rename(), or copy + delete across volumes (SD card <-> internal). */
    private fun safeMove(src: File, target: File) {
        if (src.renameTo(target)) return
        src.copyTo(target, overwrite = false)
        if (!src.delete()) {
            target.delete()
            throw IOException("couldn't remove ${src.path} after copying")
        }
    }
}
