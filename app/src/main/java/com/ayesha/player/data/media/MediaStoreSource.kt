package com.ayesha.player.data.media

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import com.ayesha.player.data.model.VideoFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Port of MediaStoreBridge.kt + media_store_service.dart, minus the channel.
 * MediaStore is the system's own video index: one fast query for the whole library, and a
 * ContentObserver for live add/delete updates. It deliberately skips `.nomedia` folders
 * (WhatsApp, Telegram), which is what the hybrid mode's filesystem walk is for.
 */
@Singleton
class MediaStoreSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    /** Every indexed video, newest first. Empty without permission or on any error. */
    @Suppress("DEPRECATION") // DATA: still the only source of an absolute path.
    suspend fun queryVideos(): List<VideoFile> = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED, // epoch SECONDS
                MediaStore.Video.Media.DURATION, // ms
            )
            val out = ArrayList<VideoFile>()
            resolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")
                ?.use { c ->
                    val dataIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    while (c.moveToNext()) {
                        val path = c.getString(dataIdx) ?: continue
                        val size = c.getLong(sizeIdx)
                        if (size < FolderScanner.MIN_VIDEO_BYTES) continue // placeholder / truncated
                        val durMs = c.getLong(durIdx)
                        out += VideoFile(
                            path = path,
                            name = c.getString(nameIdx) ?: path.substringAfterLast('/'),
                            size = size,
                            modifiedMs = c.getLong(dateIdx) * 1000L,
                            durationMs = durMs.takeIf { it > 0 },
                        )
                    }
                }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Emits whenever any video is added, changed or removed. Conflated: a burst of callbacks
     * from one file operation collapses, and the library debounces on top of that.
     */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    /**
     * The system's pre-generated thumbnail for an indexed video: near-instant compared with
     * decoding a frame. Null for unindexed videos (.nomedia folders). Blocking; call on IO.
     */
    fun loadThumbnail(path: String, width: Int, height: Int): Bitmap? {
        val id = idForPath(path) ?: return null
        val uri = ContentUris.withAppendedId(collection, id)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(uri, Size(width, height), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun idForPath(path: String): Long? = try {
        resolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    } catch (_: Exception) {
        null
    }
}
