package com.ayesha.player.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PositionEntity::class,
        DurationEntity::class,
        RecentEntity::class,
        LibraryVideoEntity::class,
        SeenPathEntity::class,
        VaultEntryEntity::class,
    ],
    version = 1,
    // Schemas go to app/schemas (see room.schemaLocation): commit them, and add a Migration
    // for every version bump. Vault entries live here, so data must never be dropped.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun positions(): PositionDao
    abstract fun durations(): DurationDao
    abstract fun recents(): RecentDao
    abstract fun library(): LibraryDao
    abstract fun seenPaths(): SeenPathDao
    abstract fun vault(): VaultDao
}
