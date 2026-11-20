package com.ayesha.player.playback.engine

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput

/**
 * ExoPlayer has no subtitle delay setting, but its text renderer picks the cue to show from the
 * playback position it's handed. This wrapper hands it the position shifted by the delay: with
 * +2 s it shows the cue of 2 s ago (later), with -2 s the one 2 s ahead (earlier; the subtitle
 * stream is buffered well ahead of playback). After a seek with a positive delay, cues from just
 * before the seek point are skipped, because the stream restarts at the seek point.
 */
@OptIn(UnstableApi::class)
private class DelayedTextRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {
    /** Written on the main thread, read on the playback thread. */
    @Volatile var delayUs = 0L

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = super.render(positionUs - delayUs, elapsedRealtimeUs)

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) =
        super.resetPosition(positionUs - delayUs, sampleStreamIsResetToKeyFrame)

    /**
     * ExoPlayer hands a bare TextRenderer the stream's end position (an instanceof check in
     * RendererHolder), so it ends with the video even when a cue, or the delay, runs past the
     * end. Wrapped, it never gets that, and "video finished" would wait for the last cue. So:
     * ended once its stream is final and fully read. The video still ends only when audio and
     * video have, and render() keeps being called meanwhile, so cues keep showing.
     */
    override fun isEnded() = super.isEnded() || (isCurrentStreamFinal() && hasReadStreamToEnd())
}

/** DefaultRenderersFactory whose text renderers follow [delayUs] (+ = subtitles later). */
@OptIn(UnstableApi::class)
internal class SubtitleDelayRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private val textRenderers = ArrayList<DelayedTextRenderer>()

    var delayUs = 0L
        set(value) {
            field = value
            textRenderers.forEach { it.delayUs = value }
        }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
        built.forEach { r ->
            val wrapped = DelayedTextRenderer(r).also { it.delayUs = delayUs }
            textRenderers += wrapped
            out += wrapped
        }
    }
}
