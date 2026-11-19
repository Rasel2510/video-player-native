package com.ayesha.player.di

import android.content.Context
import androidx.room.Room
import com.ayesha.player.data.db.AppDatabase
import com.ayesha.player.data.media.MpvSoftwareProbe
import com.ayesha.player.data.media.SoftwareProbe
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ayesha.db").build()

    @Provides fun positionDao(db: AppDatabase) = db.positions()
    @Provides fun durationDao(db: AppDatabase) = db.durations()
    @Provides fun recentDao(db: AppDatabase) = db.recents()
    @Provides fun libraryDao(db: AppDatabase) = db.library()
    @Provides fun seenPathDao(db: AppDatabase) = db.seenPaths()
    @Provides fun vaultDao(db: AppDatabase) = db.vault()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    /** libmpv probes for files MediaMetadataRetriever can't open. */
    @Binds
    abstract fun softwareProbe(impl: MpvSoftwareProbe): SoftwareProbe
}
