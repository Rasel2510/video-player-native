package com.ayesha.player.data.media

import android.media.MediaMetadataRetriever
import com.ayesha.player.core.util.Background
import com.ayesha.player.data.db.DurationDao
import com.ayesha.player.data.db.DurationEntity
import com.ayesha.player.data.db.SQL_CHUNK
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of duration_cache_service.dart: memory cache -> database -> probe
 * (MediaMetadataRetriever header read, then the software probe).
 * At most 2 probes run at once, and concurrent requests for one path share a probe.
 */
@Singleton
class DurationRepository @Inject constructor(
    private val dao: DurationDao,
    private val softwareProbe: SoftwareProbe,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Only real (positive) durations. A failed probe isn't cached, matching the Dart service. */
    private val mem = ConcurrentHashMap<String, Long>()
    private val probeSlots = Semaphore(MAX_PROBES)
    private val inFlight = HashMap<String, Deferred<Duration?>>()

    /**
     * Seeds durations already known from MediaStore, so those videos never get probed.
     * Never overwrites a value that's already cached. Memory only.
     */
    fun seed(videos: Iterable<VideoFile>) {
        for (v in videos) {
            val ms = v.durationMs ?: continue
            if (ms > 0) mem.putIfAbsent(v.path, ms)
        }
    }

    /** Memory-only read for drawing a list without suspending. */
    fun getCached(path: String): Duration? = mem[path]?.milliseconds

    /** Every cached duration for [paths], from memory or the database, without probing. */
    suspend fun loadCached(paths: List<String>): Map<String, Duration> {
        val out = HashMap<String, Duration>(paths.size)
        val missing = ArrayList<String>()
        for (p in paths) {
            val ms = mem[p]
            if (ms != null) out[p] = ms.milliseconds else missing += p
        }
        if (missing.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                missing.chunked(SQL_CHUNK).forEach { chunk ->
                    dao.getChunk(chunk).forEach { e ->
                        if (e.durationMs > 0) {
                            mem[e.path] = e.durationMs
                            out[e.path] = e.durationMs.milliseconds
                        }
                    }
                }
            }
        }
        return out
    }

    /** Cached duration, or a probe of the file when nothing is cached. Null if unreadable. */
    suspend fun getDuration(path: String): Duration? {
        mem[path]?.let { return it.milliseconds }
        val stored = withContext(Dispatchers.IO) { dao.get(path) }
        if (stored != null && stored > 0) {
            mem[path] = stored
            return stored.milliseconds
        }
        return probeDeduped(path)
    }

    /**
     * For paths [loadCached] has just missed: probes straight away instead of asking the
     * database again (one query per video, for a folder of hundreds of new videos).
     */
    suspend fun probeUncached(path: String): Duration? = mem[path]?.milliseconds ?: probeDeduped(path)

    private suspend fun probeDeduped(path: String): Duration? {
        val job = synchronized(inFlight) {
            inFlight[path] ?: scope.async(Background.media, start = CoroutineStart.LAZY) {
                try {
                    probe(path)
                } finally {
                    synchronized(inFlight) { inFlight.remove(path) }
                }
            }.also {
                inFlight[path] = it
                it.start()
            }
        }
        return job.await()
    }

    private suspend fun probe(path: String): Duration? {
        if (!File(path).exists()) return null
        return probeSlots.withPermit {
            try {
                val d = readHeaderDuration(path) ?: softwareProbe.probeDuration(path)
                // Re-checked: a file deleted mid-probe mustn't get its row written back.
                if (d != null && d.isPositive() && File(path).exists()) save(path, d)
                d
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Container header only: never opens a decoder, so it can't contend with playback. */
    private fun readHeaderDuration(path: String): Duration? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 }?.milliseconds
        } catch (_: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun save(path: String, duration: Duration) {
        val ms = duration.inWholeMilliseconds
        if (ms <= 0) return
        mem[path] = ms
        withContext(Dispatchers.IO) { dao.upsert(DurationEntity(path, ms)) }
    }

    suspend fun remove(path: String) {
        mem.remove(path)
        withContext(Dispatchers.IO) { dao.delete(path) }
    }

    suspend fun removeAll(paths: List<String>) {
        paths.forEach { mem.remove(it) }
        withContext(Dispatchers.IO) { paths.chunked(SQL_CHUNK).forEach { dao.deleteChunk(it) } }
    }

    /** Keeps a renamed file's duration so it isn't probed again. */
    suspend fun rename(oldPath: String, newPath: String) {
        mem.remove(oldPath)?.let { mem[newPath] = it }
        withContext(Dispatchers.IO) { dao.rename(oldPath, newPath) }
    }

    /** Fills in missing durations from the cache (no probing). */
    suspend fun enrich(files: List<VideoFile>): List<VideoFile> {
        val known = loadCached(files.filter { it.durationMs == null }.map { it.path })
        return files.map { f -> if (f.durationMs == null) f.withDuration(known[f.path]) else f }
    }

    private companion object {
        const val MAX_PROBES = 2
    }
}
