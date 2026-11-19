package com.ayesha.player.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ayesha.player.data.model.VideoFile

/**
 * Replaces the Flutter build's per-video SharedPreferences keys (`pos_v2_*`, `dur_v2_*`) and
 * JSON blobs (`recent_videos`, `folder_scan_cache_v2`, `folder_scan_seen_paths_v1`).
 * Keyed by the real path rather than CacheKey.sanitise(path), so "a b.mp4" and "a_b.mp4"
 * can no longer collide.
 */

@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "durations")
data class DurationEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
)

@Entity(tableName = "recents", indices = [Index("opened_at")])
data class RecentEntity(
    @PrimaryKey val path: String,
    val name: String,
    val size: Long,
    @ColumnInfo(name = "modified_ms") val modifiedMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
) {
    fun toVideoFile() = VideoFile(path, name, size, modifiedMs, durationMs)

    companion object {
        fun from(v: VideoFile, openedAt: Long) =
            RecentEntity(v.path, v.name, v.size, v.modifiedMs, v.durationMs, openedAt)
    }
}

/** The last committed library: shown instantly on the next launch, then refreshed. */
@Entity(tableName = "library_videos", indices = [Index("folder")])
data class LibraryVideoEntity(
    @PrimaryKey val path: String,
    val folder: String,
    val name: String,
    val size: Long,
    @ColumnInfo(name = "modified_ms") val modifiedMs: Long,
) {
    fun toVideoFile() = VideoFile(path, name, size, modifiedMs)
}

/** Folder and video paths the user has already been shown (drives the "NEW" badges). */
@Entity(tableName = "seen_paths")
data class SeenPathEntity(@PrimaryKey val path: String)

data class FolderCount(val folder: String, val count: Int)

/** A video moved into the vault: its file in filesDir/vault and where it came from. */
@Entity(tableName = "vault_entries")
data class VaultEntryEntity(
    @PrimaryKey @ColumnInfo(name = "vault_path") val vaultPath: String,
    @ColumnInfo(name = "original_path") val originalPath: String,
)
