package com.ayesha.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ayesha.player.core.theme.PlayerControlsStyle
import com.ayesha.player.data.library.LibraryRepository
import com.ayesha.player.data.media.ThumbnailRepository
import com.ayesha.player.data.prefs.AppearancePreferences
import com.ayesha.player.data.prefs.PlaybackPreferences
import com.ayesha.player.playback.PlayerController
import com.ayesha.player.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

/**
 * Thin screen adapter over the app-scoped [PlayerController]. Activity-scoped: the player is an
 * overlay, not a navigation destination, and each open passes its arguments to [start].
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val controller: PlayerController,
    private val library: LibraryRepository,
    private val vault: com.ayesha.player.data.vault.VaultRepository,
    private val prefs: PlaybackPreferences,
    appearance: AppearancePreferences,
    private val thumbnails: ThumbnailRepository,
) : ViewModel() {

    /** What the current player screen was opened with. */
    lateinit var args: Route.Player
        private set
    val state = controller.state
    val engine = controller.engine

    val subtitleStyle: StateFlow<SubtitleStyle> = prefs.settings.map { SubtitleStyle.from(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleStyle.from(prefs.settings.value))

    val controlsStyle: StateFlow<PlayerControlsStyle> = appearance.settings.map { it?.controlsStyle ?: PlayerControlsStyle.Tint }
        .stateIn(viewModelScope, SharingStarted.Eagerly, appearance.settings.value?.controlsStyle ?: PlayerControlsStyle.Tint)

    /** Starts playback for one open of the player (called once per player screen). */
    fun start(route: Route.Player) {
        args = route
        open(route.path, route.name, route.resumeMs)
    }

    /** "Retry" on the error screen: reopen the current video from where it was. */
    fun retry() {
        val s = state.value
        open(s.currentPath ?: args.path, s.title.ifEmpty { args.name }, s.positionMs.takeIf { it > 0 })
    }

    private fun open(path: String, name: String, resumeMs: Long?) {
        val playlist = when (val folder = args.folderPath) {
            null -> emptyList()
            Route.VAULT_PLAYLIST -> vault.videos.value
            else -> library.folder(folder)?.videos.orEmpty()
        }
        val index = playlist.indexOfFirst { it.path == path }
        controller.init(path, name, resumeMs, if (index >= 0) playlist else emptyList(), index)
    }

    suspend fun thumbnail(path: String): File? = thumbnails.getThumbnail(path)

    fun peekThumbnail(path: String): File? = thumbnails.peek(path)

    // ── Subtitle appearance (persisted) ──────────────────────────────────────
    fun adjustSubtitleFont(delta: Float) =
        prefs.setSubtitleFontSize((subtitleStyle.value.fontSize + delta).coerceIn(SubtitleStyle.MIN_FONT, SubtitleStyle.MAX_FONT))
    fun setSubtitleColor(i: Int) = prefs.setSubtitleColorIndex(i)
    fun setSubtitleBackground(on: Boolean) = prefs.setSubtitleBackground(on)
    fun setSubtitleBgColor(i: Int) = prefs.setSubtitleBgColorIndex(i)
    fun setSubtitleFont(i: Int) = prefs.setSubtitleFontIndex(i)
    fun setSubtitleOffset(xDp: Float, yDp: Float) = prefs.setSubtitlePosition(xDp, yDp)
}
