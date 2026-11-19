package com.ayesha.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [PlaybackService] bound while a player exists. Media3 promotes the bound service to a
 * foreground service with the media notification while playback runs, which is what keeps
 * audio mode and the mini player alive in the background.
 */
@Singleton
class PlaybackConnector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var future: ListenableFuture<MediaController>? = null

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        future = MediaController.Builder(context, token).buildAsync()
    }

    fun disconnect() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }
}
