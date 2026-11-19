package com.ayesha.player.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import com.ayesha.player.core.util.Background
import com.ayesha.player.core.util.CacheKey
import com.ayesha.player.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port of thumbnail_service.dart. Order of attempts:
 * disk cache -> MediaStore's own thumbnail -> frame at 1 s via MediaMetadataRetriever ->
 * software probe. JPEGs are cached in `filesDir/vid_thumbs`, the folder path_provider's
 * getApplicationSupportDirectory() used in the Flutter build.
 *
 * Failures are retried after a 45 s cooldown, at most 4 times per session, so one lost race
 * doesn't blank a thumbnail for the whole session.
 */
@Singleton
class ThumbnailRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStore: MediaStoreSource,
    private val softwareProbe: SoftwareProbe,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val cacheDir: File by lazy { File(context.filesDir, "vid_thumbs").apply { mkdirs() } }
    private val resolved = ConcurrentHashMap<String, File>()
    private val failures = ConcurrentHashMap<String, Failure>()
    private val inFlight = HashMap<String, Deferred<File?>>()
    private val slots = Semaphore(MAX_CONCURRENT) // matches Background.thumbnails

    private data class Failure(val attempts: Int, val lastTryMs: Long)

    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Paths whose thumbnail appeared after the fact (e.g. a frame captured by the playing
     * software engine). List screens listen and repaint that row.
     */
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    /** Already-known file without any I/O, for a first draw. */
    fun peek(videoPath: String): File? = resolved[videoPath]

    suspend fun getThumbnail(videoPath: String): File? {
        resolved[videoPath]?.let { return it }
        if (failureBlocked(videoPath)) return null
        val job = synchronized(inFlight) {
            inFlight[videoPath] ?: scope.async(Background.thumbnails, start = CoroutineStart.LAZY) {
                try {
                    slots.withPermit { generate(videoPath) }
                } finally {
                    synchronized(inFlight) { inFlight.remove(videoPath) }
                }
            }.also {
                inFlight[videoPath] = it
                it.start()
            }
        }
        return job.await()
    }

    private suspend fun generate(videoPath: String): File? = try {
        val target = cacheFileFor(videoPath)
        if (target.exists()) {
            resolved[videoPath] = target
            target
        } else {
            val bytes = mediaStore.loadThumbnail(videoPath, SIZE_PX, SIZE_PX)?.toJpeg(75)
                ?: extractFrame(videoPath)
                ?: softwareProbe.grabThumbnail(videoPath)
            if (bytes == null || bytes.isEmpty()) {
                recordFailure(videoPath)
                null
            } else {
                writeAtomically(target, bytes)
                resolved[videoPath] = target
                failures.remove(videoPath)
                target
            }
        }
    } catch (_: Exception) {
        recordFailure(videoPath)
        null
    }

    /**
     * Frame at 1 s (3 s cost ~2/3 more on a cold start in the Flutter build), at most 240 px wide.
     * MediaMetadataRetriever throws for codecs it can't decode; that falls through to the next step.
     */
    private fun extractFrame(videoPath: String): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(videoPath)
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE_PX, SIZE_PX)
            } else {
                r.getFrameAtTime(FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::scaleToWidth)
            }
            frame?.toJpeg(72)
        } catch (_: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Stores externally captured JPEG bytes as [videoPath]'s thumbnail (the player reuses a frame
     * from the playing software engine instead of opening a second decoder), then notifies lists.
     */
    suspend fun store(videoPath: String, bytes: ByteArray): File? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null
        try {
            val target = cacheFileFor(videoPath)
            writeAtomically(target, bytes)
            resolved[videoPath] = target
            failures.remove(videoPath)
            _updates.tryEmit(videoPath)
            target
        } catch (_: Exception) {
            null
        }
    }

    suspend fun remove(videoPath: String) = withContext(Dispatchers.IO) {
        resolved.remove(videoPath)
        failures.remove(videoPath)
        cacheFileFor(videoPath).delete()
        Unit
    }

    /** Moves a renamed file's thumbnail instead of regenerating it. */
    suspend fun rename(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        resolved.remove(oldPath)
        failures.remove(oldPath)
        val old = cacheFileFor(oldPath)
        val new = cacheFileFor(newPath)
        if (old.exists() && old.renameTo(new)) resolved[newPath] = new
        Unit
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        resolved.clear()
        failures.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun cacheFileFor(videoPath: String) = File(cacheDir, "${CacheKey.sanitise(videoPath)}.jpg")

    private fun failureBlocked(videoPath: String): Boolean {
        val f = failures[videoPath] ?: return false
        if (f.attempts >= MAX_ATTEMPTS) return true
        return SystemClock.elapsedRealtime() - f.lastTryMs < RETRY_COOLDOWN_MS
    }

    private fun recordFailure(videoPath: String) {
        failures.compute(videoPath) { _, f -> Failure((f?.attempts ?: 0) + 1, SystemClock.elapsedRealtime()) }
    }

    /** Temp file + rename: a crash mid-write can't leave a truncated JPEG that "exists". */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        recycle()
        return out.toByteArray()
    }

    private fun scaleToWidth(src: Bitmap): Bitmap {
        if (src.width <= SIZE_PX) return src
        val h = (src.height * (SIZE_PX.toFloat() / src.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, SIZE_PX, h, true).also { if (it !== src) src.recycle() }
    }

    private companion object {
        const val SIZE_PX = 240
        const val FRAME_US = 1_000_000L
        const val MAX_CONCURRENT = 3
        const val MAX_ATTEMPTS = 4
        const val RETRY_COOLDOWN_MS = 45_000L
    }
}
