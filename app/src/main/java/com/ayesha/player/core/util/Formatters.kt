package com.ayesha.player.core.util

import java.util.Calendar
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Port of duration_formatter.dart. */
object DurationFormatter {
    /** `m:ss`-style clock: `05:07`, or `1:05:07` once past an hour. */
    fun format(d: Duration): String {
        val total = d.inWholeSeconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        val mm = if (m < 10) "0$m" else "$m"
        val ss = if (s < 10) "0$s" else "$s"
        return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
    }

    fun formatMs(ms: Long): String = format(ms.milliseconds)

    /** "just now", "5m ago", "3h ago", "2d ago", then `d/M/yyyy` (no zero padding, like Dart). */
    fun timeAgo(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diff = (nowMs - epochMs).milliseconds
        return when {
            diff.inWholeMinutes < 1 -> "just now"
            diff.inWholeMinutes < 60 -> "${diff.inWholeMinutes}m ago"
            diff.inWholeHours < 24 -> "${diff.inWholeHours}h ago"
            diff.inWholeDays < 7 -> "${diff.inWholeDays}d ago"
            else -> {
                val c = Calendar.getInstance().apply { timeInMillis = epochMs }
                "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.YEAR)}"
            }
        }
    }
}

/** Port of file_size_formatter.dart. */
object FileSizeFormatter {
    private const val KB = 1024.0
    private const val MB = 1024L * 1024
    private const val GB = 1024L * 1024 * 1024

    // Locale.ROOT: always "1.5 MB", never "1,5 MB" or localized digits (matches Dart's toStringAsFixed).
    fun format(bytes: Long): String = when {
        bytes < MB -> String.format(Locale.ROOT, "%.1f KB", bytes / KB)
        bytes < GB -> String.format(Locale.ROOT, "%.1f MB", bytes / MB.toDouble())
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / GB.toDouble())
    }
}

/**
 * Port of cache_key.dart. Position, duration and thumbnail caches all key off this,
 * so it must stay byte-for-byte identical to the Dart version.
 */
object CacheKey {
    private val sanitiseRe = Regex("[^a-zA-Z0-9._\\-]")
    fun sanitise(videoPath: String): String = videoPath.replace(sanitiseRe, "_")
}

/** Minimal equivalents of package:path's basename/extension, with the same edge cases. */
object PathUtil {
    /** `/a/b/` and `/a/b` both give `b`. */
    fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** `.mp4` for `x.mp4`, empty for `x` and for dotfiles like `.nomedia`. */
    fun extension(name: String): String {
        val base = basename(name)
        val dot = base.lastIndexOf('.')
        return if (dot <= 0) "" else base.substring(dot)
    }

    fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return when {
            idx < 0 -> "."
            idx == 0 -> "/"
            else -> trimmed.substring(0, idx)
        }
    }
}
