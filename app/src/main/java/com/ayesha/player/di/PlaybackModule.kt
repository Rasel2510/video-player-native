package com.ayesha.player.di

import android.content.Context
import com.ayesha.player.playback.engine.MpvEngine
import com.ayesha.player.playback.engine.SoftwareEngineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {
    /**
     * The libmpv software engine. Null if libmpv can't start on this device (e.g. its native
     * library is missing for the ABI), in which case the player shows a clear error instead.
     */
    @Provides
    fun softwareEngines(@ApplicationContext context: Context): SoftwareEngineFactory = SoftwareEngineFactory {
        try {
            MpvEngine(context)
        } catch (_: Throwable) {
            null
        }
    }
}
