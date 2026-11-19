package com.ayesha.player.data.model

import androidx.compose.runtime.Immutable
import com.ayesha.player.core.util.FileSizeFormatter
import com.ayesha.player.core.util.PathUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of video_file.dart. The JSON shape (`path`, `name`, `size`, `modified` in epoch ms,
 * optional `duration` in ms) is identical to the Dart `toJson`, so cached data stays readable.
 */
@Immutable
@Serializable
data class VideoFile(
    val path: String,
    val name: String,
    /** Bytes. */
    val size: Long,
    /** Last-modified time, epoch milliseconds. */
    @SerialName("modified") val modifiedMs: Long,
    @SerialName("duration") val durationMs: Long? = null,
) {
    val duration: Duration? get() = durationMs?.milliseconds

    // Delegated properties have no backing field, so serialization skips them.
    // Computed once per instance: list cards read these on every recomposition.
    val extension: String by lazy { PathUtil.extension(name).lowercase() }
    val extensionLabel: String by lazy { extension.removePrefix(".").uppercase() }
    val sizeLabel: String by lazy { FileSizeFormatter.format(size) }

    fun withDuration(d: Duration?): VideoFile =
        if (d == null) this else copy(durationMs = d.inWholeMilliseconds)

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            ".mp4", ".mkv", ".mov", ".avi", ".webm",
            ".ts", ".m2ts", ".mts",
            ".flv", ".wmv", ".m4v", ".3gp", ".3g2",
            ".rmvb", ".rm", ".divx", ".xvid",
            ".vob", ".ogv", ".m2v", ".mxf", ".mpg", ".mpeg",
            ".m4b",
        )

        fun isVideoFile(filename: String): Boolean =
            PathUtil.extension(filename).lowercase() in VIDEO_EXTENSIONS

        /** Null if the file can't be stat'ed (deleted, no permission). */
        fun fromFile(file: File): VideoFile? = try {
            if (!file.isFile) {
                null
            } else {
                VideoFile(
                    path = file.path,
                    name = file.name,
                    size = file.length(),
                    modifiedMs = file.lastModified(),
                )
            }
        } catch (_: SecurityException) {
            null
        }
    }
}
