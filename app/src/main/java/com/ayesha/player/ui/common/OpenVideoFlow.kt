package com.ayesha.player.ui.common

import com.ayesha.player.data.library.VideoLauncher
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.ui.navigation.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

data class ResumePrompt(val video: VideoFile, val position: Duration, val folderPath: String?)

/**
 * "Open this video" as every screen does it: offer the saved resume point in a dialog when
 * there is one, record the open, then navigate. Owned by a ViewModel; the screen shows
 * [prompt] as a ResumeDialog and collects [navigation].
 */
class OpenVideoFlow(
    private val scope: CoroutineScope,
    private val launcher: VideoLauncher,
) {
    private val _prompt = MutableStateFlow<ResumePrompt?>(null)
    val prompt: StateFlow<ResumePrompt?> = _prompt.asStateFlow()

    private val _navigation = Channel<Route.Player>(Channel.BUFFERED)
    val navigation = _navigation.receiveAsFlow()

    /** Asks "Continue watching?" when there's a saved position, else opens from the start. */
    fun open(video: VideoFile, folderPath: String?, knownDuration: Duration? = video.duration) {
        scope.launch {
            val saved = launcher.resumePointFor(video, knownDuration)
            if (saved != null) {
                _prompt.value = ResumePrompt(video, saved, folderPath)
            } else {
                go(video, null, folderPath)
            }
        }
    }

    /** Opens at [position] without asking (Continue Watching, the resume pill and button). */
    fun openAt(video: VideoFile, position: Duration?, folderPath: String?) {
        scope.launch { go(video, position, folderPath) }
    }

    /** The dialog's answer: the saved position to resume, or Duration.ZERO to start over. */
    fun choose(start: Duration) {
        val p = _prompt.value ?: return
        _prompt.value = null
        scope.launch { go(p.video, start.takeIf { it.isPositive() }, p.folderPath) }
    }

    fun dismiss() {
        _prompt.value = null
    }

    private suspend fun go(video: VideoFile, start: Duration?, folderPath: String?) {
        launcher.recordOpen(video)
        _navigation.send(Route.Player(video.path, video.name, start?.inWholeMilliseconds, folderPath))
    }
}
