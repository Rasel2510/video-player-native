package com.ayesha.player.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

    // ── Decoded thumbnails for lists ─────────────────────────────────────────
    // Lists draw these bitmaps directly instead of running an image loader per row. A row that
    // was on screen before gets its picture synchronously, on its first frame, and creating a
    // row costs no image-request, painter or coroutine machinery.

    private val bitmaps = object : LruCache<String, ImageBitmap>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Decoding cached JPEGs is quick: kept off the slow generation threads so it never queues behind them. */
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)

    private fun bitmapKey(videoPath: String, w: Int, h: Int) = "$videoPath|$w|$h"

    /** The decoded thumbnail at [w]x[h] px if it's in memory; no I/O. */
    fun peekBitmap(videoPath: String, w: Int, h: Int): ImageBitmap? = bitmaps.get(bitmapKey(videoPath, w, h))

    /**
     * The thumbnail decoded for a [w]x[h] px slot (scaled to cover it, not cropped), generating
     * the JPEG first if needed. Null when no thumbnail can be made.
     */
    suspend fun loadBitmap(videoPath: String, w: Int, h: Int): ImageBitmap? {
        val key = bitmapKey(videoPath, w, h)
        bitmaps.get(key)?.let { return it }
        val file = getThumbnail(videoPath) ?: return null
        return withContext(decodeDispatcher) {
            decodeCover(file, w, h)?.also {
                // Not if the video was removed while this decoded: removeAll has already evicted,
                // and a later file at the same path would get this picture.
                if (resolved[videoPath] == file) bitmaps.put(key, it)
            }
        }
    }

    private fun decodeCover(file: File, w: Int, h: Int): ImageBitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                // Scaled while decoding to just cover the slot: no full-size bitmap, no second pass.
                val sw = info.size.width
                val sh = info.size.height
                val scale = maxOf(w.toFloat() / sw, h.toFloat() / sh).coerceAtMost(1f)
                decoder.setTargetSize((sw * scale).toInt().coerceAtLeast(1), (sh * scale).toInt().coerceAtLeast(1))
                // Uploaded to the GPU here, on this thread, instead of on the first draw.
                decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            }.asImageBitmap()
        } else {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
            BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
        }
    } catch (_: Exception) {
        null
    }

    private fun evictBitmaps(videoPath: String) {
        val prefix = "$videoPath|"
        bitmaps.snapshot().keys.filter { it.startsWith(prefix) }.forEach { bitmaps.remove(it) }
    }

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
                if (sourceGone(videoPath)) {
                    // Deleted while this ran: remove() has been and gone, so no orphan JPEG.
                    target.delete()
                    null
                } else {
                    resolved[videoPath] = target
                    failures.remove(videoPath)
                    target
                }
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
        // A frame from a player still holding a deleted file mustn't recreate its thumbnail.
        if (bytes.isEmpty() || sourceGone(videoPath)) return@withContext null
        try {
            val target = cacheFileFor(videoPath)
            writeAtomically(target, bytes)
            resolved[videoPath] = target
            failures.remove(videoPath)
            evictBitmaps(videoPath)
            _updates.tryEmit(videoPath)
            target
        } catch (_: Exception) {
            null
        }
    }

    suspend fun remove(videoPath: String) = removeAll(listOf(videoPath))

    /** Deleted videos: their JPEGs on disk and their decoded bitmaps in memory. */
    suspend fun removeAll(videoPaths: Collection<String>) = withContext(Dispatchers.IO) {
        if (videoPaths.isEmpty()) return@withContext
        val gone = videoPaths.toHashSet()
        // One pass over the bitmap cache for the whole batch, not one per video.
        bitmaps.snapshot().keys.filter { it.substringBeforeLast('|').substringBeforeLast('|') in gone }
            .forEach { bitmaps.remove(it) }
        for (p in gone) {
            resolved.remove(p)
            failures.remove(p)
            cacheFileFor(p).delete()
        }
    }

    /** Only local files can be checked; content URIs are left alone. */
    private fun sourceGone(videoPath: String) = videoPath.startsWith('/') && !File(videoPath).exists()

    /** Moves a renamed file's thumbnail instead of regenerating it. */
    suspend fun rename(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        evictBitmaps(oldPath)
        resolved.remove(oldPath)
        failures.remove(oldPath)
        val old = cacheFileFor(oldPath)
        val new = cacheFileFor(newPath)
        if (old.exists() && old.renameTo(new)) resolved[newPath] = new
        Unit
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        bitmaps.evictAll()
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

        /** ~290 list thumbnails (88x58 dp at 2x is ~80 KB each). */
        val BITMAP_CACHE_BYTES = minOf(24 shl 20, (Runtime.getRuntime().maxMemory() / 8).toInt())
    }
}
