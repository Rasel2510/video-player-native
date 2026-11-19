package com.ayesha.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** SQLite caps bound variables (999 on older devices), so IN (...) lookups go in chunks. */
internal const val SQL_CHUNK = 900

@Dao
interface PositionDao {
    @Query("SELECT position_ms FROM positions WHERE path = :path")
    suspend fun get(path: String): Long?

    @Query("SELECT * FROM positions WHERE path IN (:paths)")
    suspend fun getChunk(paths: List<String>): List<PositionEntity>

    @Upsert
    suspend fun upsert(e: PositionEntity)

    @Query("DELETE FROM positions WHERE path = :path")
    suspend fun delete(path: String)

    /** Write the new key first, drop the old one only after (same order as PositionService.rename). */
    @Transaction
    suspend fun rename(oldPath: String, newPath: String, now: Long) {
        val ms = get(oldPath) ?: return
        upsert(PositionEntity(newPath, ms, now))
        delete(oldPath)
    }
}

@Dao
interface DurationDao {
    @Query("SELECT duration_ms FROM durations WHERE path = :path")
    suspend fun get(path: String): Long?

    @Query("SELECT * FROM durations WHERE path IN (:paths)")
    suspend fun getChunk(paths: List<String>): List<DurationEntity>

    @Upsert
    suspend fun upsert(e: DurationEntity)

    @Query("DELETE FROM durations WHERE path = :path")
    suspend fun delete(path: String)

    @Transaction
    suspend fun rename(oldPath: String, newPath: String) {
        val ms = get(oldPath) ?: return
        upsert(DurationEntity(newPath, ms))
        delete(oldPath)
    }
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recents ORDER BY opened_at DESC")
    suspend fun all(): List<RecentEntity>

    @Upsert
    suspend fun upsert(e: RecentEntity)

    @Query("DELETE FROM recents WHERE path NOT IN (SELECT path FROM recents ORDER BY opened_at DESC LIMIT :max)")
    suspend fun trimTo(max: Int)

    @Query("DELETE FROM recents WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM recents")
    suspend fun clear()

    @Transaction
    suspend fun addAndTrim(e: RecentEntity, max: Int) {
        upsert(e)
        trimTo(max)
    }
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_videos")
    suspend fun all(): List<LibraryVideoEntity>

    /** Folder -> video count of the cached library: the "snapshot" the quick change check compares. */
    @Query("SELECT folder, COUNT(*) AS count FROM library_videos GROUP BY folder")
    suspend fun folderCounts(): List<FolderCount>

    @Query("SELECT COUNT(*) FROM library_videos")
    suspend fun count(): Int

    @Query("DELETE FROM library_videos")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<LibraryVideoEntity>)

    /** Cache and snapshot are one table, so they can never disagree (Flutter's FIX #ATOMIC). */
    @Transaction
    suspend fun replaceAll(rows: List<LibraryVideoEntity>) {
        clear()
        rows.chunked(500).forEach { insertAll(it) }
    }
}

@Dao
interface SeenPathDao {
    @Query("SELECT path FROM seen_paths")
    suspend fun all(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<SeenPathEntity>)

    @Query("DELETE FROM seen_paths")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(paths: Collection<String>) {
        clear()
        paths.map(::SeenPathEntity).chunked(500).forEach { insertAll(it) }
    }
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries")
    suspend fun all(): List<VaultEntryEntity>

    @Query("SELECT original_path FROM vault_entries WHERE vault_path = :vaultPath")
    suspend fun originalFor(vaultPath: String): String?

    @Upsert
    suspend fun upsert(e: VaultEntryEntity)

    @Query("DELETE FROM vault_entries WHERE vault_path IN (:paths)")
    suspend fun deleteAll(paths: List<String>)
}
