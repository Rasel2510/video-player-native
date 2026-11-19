package com.ayesha.player.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ayesha.player.playback.FitMode
import com.ayesha.player.playback.engine.PlayerEngine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/** Pure fit math from player_video_layer.dart: the video's on-screen size for [fit]. */
fun fitVideo(fit: FitMode, videoW: Float, videoH: Float, boxW: Float, boxH: Float): Pair<Float, Float> {
    val va = videoW / videoH
    val ba = boxW / boxH
    return when (fit) {
        FitMode.Fill -> boxW to boxH
        FitMode.Cover -> if (va > ba) boxH * va to boxH else boxW to boxW / va
        // Like contain, but never upscale past the video's real size.
        FitMode.Natural -> when {
            videoW <= boxW && videoH <= boxH -> videoW to videoH
            va > ba -> boxW to boxW / va
            else -> boxH * va to boxH
        }
        FitMode.Contain -> if (va > ba) boxW to boxW / va else boxH * va to boxH
    }
}

/**
 * Lets the screen freeze the video for an exit animation. A SurfaceView is composited by the
 * system, outside the app's drawing, so it can't fade or scale with the screen: animating it
 * leaves a hard-edged video rectangle floating over the list. [freeze] copies the current frame
 * into a bitmap and draws that over the surface instead, which animates like any image.
 */
@Stable
class VideoSurfaceHandle {
    internal var view: SurfaceView? = null

    var frozen by mutableStateOf(false)
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    suspend fun freeze() {
        if (frozen) return
        frame = capture()
        frozen = true
    }

    /** Where the video is drawn, in window pixels (the zoomed size, which may extend off screen). */
    fun windowBounds(): Rect? {
        val v = view ?: return null
        if (!v.isAttachedToWindow || v.width <= 0 || v.height <= 0) return null
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return Rect(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat())
    }

    private var warmed = false

    /** One throwaway 8 px copy, so the first real copy doesn't pay the GPU setup. Once per screen. */
    suspend fun warmUp() {
        if (warmed || frozen) return
        warmed = true
        capture(maxSide = 8f)
    }

    /** At most ~150 ms; null when there's no frame yet (still loading) or the copy fails. */
    private suspend fun capture(maxSide: Float = 960f): ImageBitmap? {
        val v = view ?: return null
        if (v.width <= 0 || v.height <= 0 || !v.holder.surface.isValid) return null
        // A still that shrinks into a card within 400 ms doesn't need more than ~960 px.
        val scale = min(1f, maxSide / max(v.width, v.height))
        val bmp = runCatching {
            Bitmap.createBitmap((v.width * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        return withTimeoutOrNull(150) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    PixelCopy.request(v, bmp, { result ->
                        if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bmp.asImageBitmap() else null)
                    }, Handler(Looper.getMainLooper()))
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }
}

/**
 * The video: a SurfaceView sized for the fit mode and zoom, centered and clipped to the
 * container. Always composed (even before the first frame) so the engine has a surface to
 * render into; reattaches itself when the engine changes (software fallback).
 */
@Composable
fun VideoSurface(
    engine: PlayerEngine?,
    videoWidth: Int,
    videoHeight: Int,
    fit: FitMode,
    zoom: Float,
    modifier: Modifier = Modifier,
    /** Pixels per dp: [videoWidth]/[videoHeight] are pixels, the layout is in dp. */
    density: Float,
    handle: VideoSurfaceHandle? = null,
) {
    var surfaceHolder by remember { mutableStateOf<SurfaceHolder?>(null) }
    // The SurfaceView's callback outlives recompositions: always reach the current engine.
    val currentEngine by rememberUpdatedState(engine)

    LaunchedEffect(engine, surfaceHolder) {
        val h = surfaceHolder
        val valid = h != null && h.surface.isValid
        if (valid) {
            engine?.setSurface(h.surface)
            h.surfaceFrame.let { engine?.setSurfaceSize(it.width(), it.height()) }
        }
    }
    DisposableEffect(engine) {
        // Only our own surface: the mini player may have taken the engine over by now.
        onDispose { surfaceHolder?.surface?.let { engine?.detachSurface(it) } }
    }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val vw = (if (videoWidth > 0) videoWidth else 16).toFloat() / density
        val vh = (if (videoHeight > 0) videoHeight else 9).toFloat() / density
        val (w, h) = fitVideo(fit, vw, vh, maxWidth.value, maxHeight.value)
        Box(Modifier.requiredSize(Dp(w * zoom), Dp(h * zoom))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        handle?.view = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceHolder = holder
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                currentEngine?.setSurfaceSize(width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                // Detach synchronously: the surface is invalid once this returns.
                                currentEngine?.detachSurface(holder.surface)
                                surfaceHolder = null
                            }
                        })
                    }
                },
                onRelease = { v -> handle?.let { if (it.view === v) it.view = null } },
            )
            if (handle != null && handle.frozen) {
                // Drawn over the SurfaceView's hole, so the window shows this instead of the video.
                val still = handle.frame
                if (still != null) {
                    Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
    }
}
