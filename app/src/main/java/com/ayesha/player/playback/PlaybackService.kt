package com.ayesha.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ayesha.player.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Media3 session service: the media notification, lock-screen controls, headset buttons and
 * the foreground service that keeps audio mode / the mini player playing in the background.
 * Replaces PlaybackService.kt, MediaActionReceiver and the MediaSessionCompat code of the
 * Flutter build's MainActivity.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var controller: PlayerController

    private val scope = MainScope()
    private var session: MediaSession? = null
    private var player: ControllerPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val p = ControllerPlayer(controller)
        player = p
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, p).setSessionActivity(open).build()

        // Refresh the session on changes that matter to it. Position is read live through the
        // player's supplier; while paused it's included (by the second) so a seek shows up.
        scope.launch {
            combine(
                controller.state.map { s ->
                    listOf(
                        s.isActive, s.currentPath, s.title, s.intendsToPlay, s.isInitialized, s.hasEnded,
                        s.durationMs, s.playbackSpeed, s.currentIndex, s.folderVideos.size,
                        if (s.isPlaying) -1L else s.positionMs / 1000,
                    )
                }.distinctUntilChanged(),
                controller.artPath,
            ) { a, b -> a to b }.collect { p.refresh() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: keep going only while actually playing (audio mode). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!controller.state.value.isPlaying) {
            controller.dispose()
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
