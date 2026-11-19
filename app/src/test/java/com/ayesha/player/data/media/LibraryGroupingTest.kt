package com.ayesha.player.data.media

import com.ayesha.player.data.library.PositionRules
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.model.VideoFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupingTest {

    private fun v(path: String) = VideoFile(path, path.substringAfterLast('/'), 2048, 0)

    private val movies = VideoFolder("/s/Movies", listOf(v("/s/Movies/a.mp4"), v("/s/Movies/b.mp4")))
    private val cam = VideoFolder("/s/Camera", listOf(v("/s/Camera/c.mp4")))

    @Test fun `group by folder matches the scanner's shape`() {
        val grouped = LibraryGrouping.groupByFolder(listOf(v("/s/Movies/b.mp4"), v("/s/Camera/c.mp4"), v("/s/Movies/a.mp4")))
        assertEquals(listOf(cam, movies), grouped)
    }

    @Test fun `same video set ignores order and grouping`() {
        val flat = LibraryGrouping.groupByFolder(listOf(v("/s/Camera/c.mp4"), v("/s/Movies/a.mp4"), v("/s/Movies/b.mp4")))
        assertTrue(LibraryGrouping.sameVideoSet(listOf(movies, cam), flat))
        assertFalse(LibraryGrouping.sameVideoSet(listOf(movies), flat))
        assertFalse(LibraryGrouping.sameVideoSet(listOf(movies, cam), listOf(movies)))
    }

    @Test fun `first install shows no badges and seeds the seen set`() {
        val r = LibraryGrouping.computeBadges(listOf(movies, cam), seen = null, sessionSeen = emptySet())
        assertTrue(r.newPaths.isEmpty())
        assertEquals(setOf("/s/Movies", "/s/Movies/a.mp4", "/s/Movies/b.mp4", "/s/Camera", "/s/Camera/c.mp4"), r.seenToPersist)
    }

    @Test fun `a new video badges itself and its folder`() {
        val seen = setOf("/s/Movies", "/s/Movies/a.mp4", "/s/Camera", "/s/Camera/c.mp4", "/gone/old.mp4")
        val r = LibraryGrouping.computeBadges(listOf(movies, cam), seen, sessionSeen = emptySet())
        assertEquals(setOf("/s/Movies/b.mp4", "/s/Movies"), r.newPaths)
        // Stale path pruned; the folder stays "seen" on disk even while badged by its video.
        assertFalse("/gone/old.mp4" in r.seenToPersist)
        assertTrue("/s/Movies" in r.seenToPersist)
    }

    @Test fun `dismissed this session stays dismissed`() {
        val seen = setOf("/s/Camera", "/s/Camera/c.mp4")
        val r = LibraryGrouping.computeBadges(listOf(movies, cam), seen, sessionSeen = setOf("/s/Movies", "/s/Movies/a.mp4", "/s/Movies/b.mp4"))
        assertTrue(r.newPaths.isEmpty())
    }

    @Test fun `cached badges need an initialised seen set`() {
        assertTrue(LibraryGrouping.cachedBadges(listOf(movies), null, emptySet()).isEmpty())
        assertEquals(
            setOf("/s/Movies", "/s/Movies/b.mp4"),
            LibraryGrouping.cachedBadges(listOf(movies), setOf("/s/Movies/a.mp4"), emptySet()),
        )
    }

    @Test fun `removing the last new video clears the folder badge`() {
        val remaining = listOf(VideoFolder("/s/Movies", listOf(v("/s/Movies/a.mp4"))))
        val pruned = LibraryGrouping.pruneNewPathsForRemovedVideos(
            setOf("/s/Movies", "/s/Movies/b.mp4"), remaining, listOf("/s/Movies/b.mp4"),
        )
        assertTrue(pruned.isEmpty())
    }

    @Test fun `resume position rules`() {
        assertEquals(PositionRules.Action.Ignore, PositionRules.actionFor(4_999, 60_000))
        assertEquals(PositionRules.Action.Ignore, PositionRules.actionFor(30_000, 0))
        assertEquals(PositionRules.Action.Save, PositionRules.actionFor(30_000, 60_000))
        assertEquals(PositionRules.Action.Clear, PositionRules.actionFor(56_000, 60_000))
    }
}
