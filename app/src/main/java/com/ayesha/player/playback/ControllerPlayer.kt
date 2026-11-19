package com.ayesha.player.playback

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * Presents [PlayerController] to Media3's MediaSession, so the notification, lock screen and
 * headset buttons drive whichever engine is playing (ExoPlayer or the software fallback).
 * The folder is exposed as the playlist, so next/previous come for free.
 * Replaces media_session_service.dart and MainActivity's MediaSessionCompat code.
 */
@OptIn(UnstableApi::class)
class ControllerPlayer(private val controller: PlayerController) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_RELEASE,
    ).build()

    /** Re-reads the controller's state (called by the service on every relevant change). */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = controller.state.value
        val path = s.currentPath
        if (!s.isActive || path == null) {
            return State.Builder().setAvailableCommands(commands).setPlaybackState(Player.STATE_IDLE).build()
        }
        val art = controller.artPath.value
        val usePlaylist = s.folderVideos.isNotEmpty() && s.currentIndex in s.folderVideos.indices
        val items = if (usePlaylist) {
            s.folderVideos.mapIndexed { i, v -> item(v.path, v.name, i == s.currentIndex, s.durationMs, art) }
        } else {
            listOf(item(path, s.title, true, s.durationMs, art))
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(if (usePlaylist) s.currentIndex else 0)
            .setPlayWhenReady(s.intendsToPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when {
                    s.hasEnded -> Player.STATE_ENDED
                    !s.isInitialized -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                },
            )
            .setContentPositionMs { controller.currentPositionMs() }
            .setPlaybackParameters(PlaybackParameters(s.playbackSpeed))
            .build()
    }

    private fun item(path: String, title: String, current: Boolean, durationMs: Long, art: String?): MediaItemData =
        MediaItemData.Builder(path)
            .setMediaItem(MediaItem.Builder().setMediaId(path).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (current && art != null) setArtworkUri(Uri.fromFile(File(art))) }
                    .build(),
            )
            .setDurationUs(if (current && durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
            .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val s = controller.state.value
        val usePlaylist = s.folderVideos.isNotEmpty() && s.currentIndex >= 0
        if (usePlaylist && mediaItemIndex != s.currentIndex && mediaItemIndex in s.folderVideos.indices) {
            controller.playIndex(mediaItemIndex)
        } else if (positionMs != C.TIME_UNSET) {
            controller.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
