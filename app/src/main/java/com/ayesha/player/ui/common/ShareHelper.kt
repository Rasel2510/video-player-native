package com.ayesha.player.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.ayesha.player.data.model.VideoFile
import java.io.File

/** share_plus + Clipboard replacements. */
object ShareHelper {

    /** One share sheet for one or many videos (share_plus's shareXFiles). */
    fun share(context: Context, videos: List<VideoFile>) {
        if (videos.isEmpty()) return
        val uris = ArrayList<Uri>(videos.mapNotNull { uriFor(context, it.path) })
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris[0])
                putExtra(Intent.EXTRA_TEXT, videos[0].name)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }.apply {
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }

    fun copyToClipboard(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("path", text))
    }

    private fun uriFor(context: Context, path: String): Uri? =
        if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(path)) }.getOrNull()
        }
}
