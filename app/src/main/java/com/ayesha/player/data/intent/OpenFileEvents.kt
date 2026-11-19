package com.ayesha.player.data.intent

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.ayesha.player.data.model.VideoFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Videos opened from outside the app ("Open with" / VIEW intents).
 * Replaces open_file_service.dart + the openFile MethodChannel in the Flutter build.
 * The home screen collects [pending] and calls [consume] once it has navigated to the player.
 */
@Singleton
class OpenFileEvents @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun handle(intent: Intent?, resolver: ContentResolver) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        _pending.value = resolveToPath(uri, resolver)
    }

    fun consume() {
        _pending.value = null
    }

    /**
     * A VideoFile for a picked document (home screen's "Open file..."): real path when it
     * can be resolved, display name and size from the provider.
     */
    fun videoFromUri(uri: Uri, resolver: ContentResolver): VideoFile {
        val path = resolveToPath(uri, resolver)
        var name: String? = null
        var size = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        } catch (_: Exception) {
        }
        return VideoFile(
            path = path,
            name = name ?: nameFromPath(path),
            size = size,
            modifiedMs = System.currentTimeMillis(),
        )
    }

    /** Last path segment, without a query string (same as home_screen.dart's _openExternal). */
    fun nameFromPath(path: String): String = path.substringAfterLast('/').substringBefore('?')

    /** A real file path when resolvable, otherwise the raw URI string (ExoPlayer plays both). */
    private fun resolveToPath(uri: Uri, resolver: ContentResolver): String = when (uri.scheme) {
        "file" -> uri.path ?: uri.toString()
        "content" -> queryDataColumn(uri, resolver) ?: uri.toString()
        else -> uri.toString()
    }

    // DATA is deprecated but still the only way to get a filesystem path, which the
    // resume-position and recents stores use as their key.
    @Suppress("DEPRECATION")
    private fun queryDataColumn(uri: Uri, resolver: ContentResolver): String? = try {
        resolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.Video.Media.DATA)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }
}
