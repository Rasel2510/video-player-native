package com.ayesha.player.data.model

import androidx.compose.runtime.Immutable
import com.ayesha.player.core.util.FileSizeFormatter
import com.ayesha.player.core.util.PathUtil

/** Port of video_folder.dart. */
@Immutable
data class VideoFolder(
    val path: String,
    val videos: List<VideoFile>,
) {
    val name: String by lazy { PathUtil.basename(path).ifEmpty { path } }
    val totalSize: Long by lazy { videos.sumOf { it.size } }
    val totalSizeLabel: String by lazy { FileSizeFormatter.format(totalSize) }
    val videoCount: Int get() = videos.size
}
