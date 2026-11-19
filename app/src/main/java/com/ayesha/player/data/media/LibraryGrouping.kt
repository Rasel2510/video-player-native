package com.ayesha.player.data.media

import com.ayesha.player.core.util.PathUtil
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.model.VideoFolder

/** Pure helpers from folders_provider.dart, kept free of Android so they're unit-tested. */
object LibraryGrouping {

    /** Folders and the videos inside them, both sorted by lower-cased name. */
    fun sortedFolders(map: Map<String, List<VideoFile>>): List<VideoFolder> =
        map.map { (path, videos) -> VideoFolder(path, videos.sortedBy { it.name.lowercase() }) }
            .sortedBy { it.name.lowercase() }

    /** Groups a flat list by parent directory: the same shape the filesystem scanner produces. */
    fun groupByFolder(videos: Collection<VideoFile>): List<VideoFolder> =
        sortedFolders(videos.groupBy { PathUtil.parent(it.path) })

    /** True when both contain exactly the same video paths, ignoring grouping and order. */
    fun sameVideoSet(current: List<VideoFolder>, merged: List<VideoFolder>): Boolean {
        val currentPaths = HashSet<String>()
        current.forEach { f -> f.videos.forEach { currentPaths += it.path } }
        var count = 0
        for (f in merged) for (v in f.videos) {
            count++
            if (v.path !in currentPaths) return false
        }
        return count == currentPaths.size
    }

    data class BadgeResult(val newPaths: Set<String>, val seenToPersist: Set<String>)

    /**
     * Port of `_applyFolders`' badge logic. A folder or video path is new only when:
     *  1. the seen set was initialised ([seen] != null; null means first install, so no badges),
     *  2. the seen set isn't empty (an empty first scan gives no badges),
     *  3. the path isn't in [seen], and
     *  4. it wasn't dismissed this session ([sessionSeen]).
     * A new video also keeps its folder's badge alive. [BadgeResult.seenToPersist] is the
     * pruned seen set to save (stale paths of deleted videos dropped; Flutter's FIX #4).
     */
    fun computeBadges(
        merged: List<VideoFolder>,
        seen: Set<String>?,
        sessionSeen: Set<String>,
    ): BadgeResult {
        val all = HashSet<String>()
        merged.forEach { f ->
            all += f.path
            f.videos.forEach { all += it.path }
        }
        val persist = HashSet<String>(seen?.intersect(all) ?: emptySet())
        val newPaths = HashSet<String>()
        val initialised = !seen.isNullOrEmpty()

        for (folder in merged) {
            val folderOnDisk = seen?.contains(folder.path) == true
            if (initialised && !folderOnDisk && folder.path !in sessionSeen) {
                newPaths += folder.path
            } else {
                persist += folder.path
            }
            for (video in folder.videos) {
                val videoOnDisk = seen?.contains(video.path) == true
                if (initialised && !videoOnDisk && video.path !in sessionSeen) {
                    newPaths += video.path
                    newPaths += folder.path
                } else {
                    persist += video.path
                }
            }
        }
        return BadgeResult(newPaths, persist)
    }

    /** Badges for a cached library shown at launch (`_showCache`): nothing is persisted here. */
    fun cachedBadges(folders: List<VideoFolder>, seen: Set<String>?, sessionSeen: Set<String>): Set<String> {
        if (seen.isNullOrEmpty()) return emptySet()
        val out = HashSet<String>()
        for (f in folders) {
            if (f.path !in seen && f.path !in sessionSeen) out += f.path
            for (v in f.videos) {
                if (v.path !in seen && v.path !in sessionSeen) {
                    out += v.path
                    out += f.path
                }
            }
        }
        return out
    }

    /** After deleting videos: drop their badges, and a folder's badge once none of its videos is new. */
    fun pruneNewPathsForRemovedVideos(
        currentNewPaths: Set<String>,
        folders: List<VideoFolder>,
        removedPaths: Collection<String>,
    ): Set<String> {
        val updated = HashSet(currentNewPaths)
        updated.removeAll(removedPaths.toSet())
        for (folder in folders) {
            if (folder.videos.none { it.path in updated }) updated -= folder.path
        }
        return updated
    }
}
