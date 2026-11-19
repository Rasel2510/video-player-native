package com.ayesha.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FormattersTest {

    @Test fun `duration under an hour is mm-ss`() {
        assertEquals("00:00", DurationFormatter.format(0.seconds))
        assertEquals("00:07", DurationFormatter.format(7.seconds))
        assertEquals("05:07", DurationFormatter.format(5.minutes + 7.seconds))
        assertEquals("59:59", DurationFormatter.format(59.minutes + 59.seconds))
    }

    @Test fun `duration past an hour has unpadded hours`() {
        assertEquals("1:00:00", DurationFormatter.format(1.hours))
        assertEquals("1:02:05", DurationFormatter.formatMs(3_725_000))
        assertEquals("12:00:09", DurationFormatter.format(12.hours + 9.seconds))
    }

    @Test fun `duration truncates sub-second parts like Dart inSeconds`() {
        assertEquals("00:01", DurationFormatter.formatMs(1_999))
    }

    @Test fun `timeAgo buckets`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", DurationFormatter.timeAgo(now - 30_000, now))
        assertEquals("5m ago", DurationFormatter.timeAgo(now - 5 * 60_000, now))
        assertEquals("3h ago", DurationFormatter.timeAgo(now - 3 * 3_600_000, now))
        assertEquals("2d ago", DurationFormatter.timeAgo(now - 2 * 86_400_000L, now))
    }

    @Test fun `timeAgo after a week is an unpadded date`() {
        val then = Calendar.getInstance().apply { set(2024, Calendar.MARCH, 5, 12, 0, 0) }.timeInMillis
        assertEquals("5/3/2024", DurationFormatter.timeAgo(then, then + 30L * 86_400_000))
    }

    @Test fun `file sizes match Dart toStringAsFixed`() {
        assertEquals("0.0 KB", FileSizeFormatter.format(0))
        assertEquals("1.0 KB", FileSizeFormatter.format(1024))
        assertEquals("1023.0 KB", FileSizeFormatter.format(1023L * 1024))
        assertEquals("1.5 MB", FileSizeFormatter.format(1536L * 1024))
        assertEquals("3.20 GB", FileSizeFormatter.format(3_435_973_837L))
    }

    @Test fun `cache key replaces everything outside the allowed set`() {
        assertEquals(
            "_storage_emulated_0_Movies_My_Film__2024_.mkv",
            CacheKey.sanitise("/storage/emulated/0/Movies/My Film (2024).mkv"),
        )
        assertEquals("a-b_c.d", CacheKey.sanitise("a-b_c.d"))
        // One underscore per UTF-16 unit, like Dart's non-unicode RegExp: 3 Bengali chars + "/".
        assertEquals("____", CacheKey.sanitise("ছবি/"))
    }

    @Test fun `path helpers follow package path`() {
        assertEquals("Movies", PathUtil.basename("/storage/emulated/0/Movies"))
        assertEquals("Movies", PathUtil.basename("/storage/emulated/0/Movies/"))
        assertEquals(".mp4", PathUtil.extension("clip.final.mp4"))
        assertEquals("", PathUtil.extension("README"))
        assertEquals("", PathUtil.extension(".nomedia"))
        assertEquals("/storage/emulated/0", PathUtil.parent("/storage/emulated/0/Movies"))
        assertEquals("/", PathUtil.parent("/Movies"))
    }
}
