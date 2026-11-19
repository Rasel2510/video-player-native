package com.ayesha.player.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.core.util.DurationFormatter
import com.ayesha.player.data.media.ThumbnailRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.time.Duration
import androidx.compose.ui.platform.LocalContext

/**
 * One shimmer value shared by every loading thumbnail (Flutter's FIX #OPT-8: 50 loading tiles
 * used to run 50 animation controllers). Provide once with [ShimmerScope].
 *
 * The clock only runs while at least one placeholder is on screen. An always-running infinite
 * animation woke the app on every display frame, even during video playback with no list visible.
 */
@Stable
private class Shimmer {
    var users by mutableIntStateOf(0)
    val alpha = mutableFloatStateOf(0.08f)
}

private val LocalShimmer = staticCompositionLocalOf { Shimmer() }

@Composable
fun ShimmerScope(content: @Composable () -> Unit) {
    val shimmer = remember { Shimmer() }
    LaunchedEffect(shimmer) {
        snapshotFlow { shimmer.users > 0 }.collectLatest { running ->
            if (!running) return@collectLatest
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { now ->
                    // 0.04 -> 0.12 -> 0.04 over 2 s, eased each way (the Flutter pulse).
                    val phase = ((now - start) % 2000L) / 1000f
                    val x = if (phase <= 1f) phase else 2f - phase
                    shimmer.alpha.floatValue = 0.04f + 0.08f * FastOutSlowInEasing.transform(x)
                }
            }
        }
    }
    CompositionLocalProvider(LocalShimmer provides shimmer, content = content)
}

/** A loading placeholder: counts itself in while composed, and reads the pulse while drawing. */
@Composable
private fun ShimmerBox(modifier: Modifier) {
    val shimmer = LocalShimmer.current
    DisposableEffect(shimmer) {
        shimmer.users++
        onDispose { shimmer.users-- }
    }
    Box(modifier.drawBehind { drawRect(Color.White.copy(alpha = shimmer.alpha.floatValue)) })
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThumbnailEntryPoint {
    fun thumbnails(): ThumbnailRepository
}

/** Resolved once for the whole app (it used to be looked up again in every list row). */
private var thumbnailRepo: ThumbnailRepository? = null

private fun thumbnails(context: android.content.Context): ThumbnailRepository =
    thumbnailRepo ?: EntryPointAccessors.fromApplication(context.applicationContext, ThumbnailEntryPoint::class.java)
        .thumbnails().also { thumbnailRepo = it }

private val PlayDisc = Color(0x80000000)
private val ChipBg = Color(0xB3000000)
private val ChipShape = RoundedCornerShape(4.dp)
private val DurationStyle = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.W600, letterSpacing = 0.2.sp)

/** Icons.Rounded.PlayArrow's outline, in its 24-unit viewport: parsed once, drawn by every row. */
private val PlayGlyph: Path by lazy {
    PathParser().parsePathString(
        "M8 6.82v10.36c0 .79.87 1.27 1.54.84l8.14-5.18c.62-.39.62-1.29 0-1.69L9.54 5.98C8.87 5.55 8 6.03 8 6.82z",
    ).toPath()
}

/**
 * thumbnail_widget.dart: the thumbnail with a play glyph and the duration chip once loaded,
 * a shimmer while loading, and a movie icon if none can be made.
 *
 * Built for long lists: the picture, the dark disc and the play glyph are drawn by one node
 * from a bitmap the repository decoded off the main thread, and a thumbnail seen before is
 * there on the row's first frame. (An image loader per row cost about a millisecond and a half
 * of main-thread work for every row scrolled into view on a budget phone.)
 */
@Composable
fun VideoThumbnail(videoPath: String, width: Dp, height: Dp, duration: Duration?, modifier: Modifier = Modifier) {
    val repo = thumbnails(LocalContext.current)
    val density = LocalDensity.current
    val pxW = with(density) { width.roundToPx() }
    val pxH = with(density) { height.roundToPx() }
    var image by remember(videoPath, pxW, pxH) { mutableStateOf(repo.peekBitmap(videoPath, pxW, pxH)) }
    var failed by remember(videoPath) { mutableStateOf(false) }

    // Only rows without a picture in memory start any work.
    if (image == null) {
        LaunchedEffect(videoPath, pxW, pxH) {
            val got = repo.loadBitmap(videoPath, pxW, pxH)
            if (got != null) {
                image = got
                return@LaunchedEffect
            }
            failed = true
            // It may still arrive later (e.g. captured by the playing software engine).
            repo.updates.first { it == videoPath }
            image = repo.loadBitmap(videoPath, pxW, pxH)
            failed = image == null
        }
    }

    val img = image
    when {
        // heroSource: the player grows out of this thumbnail and shrinks back into it.
        img != null -> Box(modifier.size(width, height).heroSource(videoPath, img).thumbnailImage(img)) {
            if (duration != null && duration.inWholeSeconds > 0) {
                Text(
                    DurationFormatter.format(duration),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(ChipBg, ChipShape)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    style = DurationStyle,
                )
            }
        }
        failed -> Box(modifier.size(width, height).background(AppTheme.colors.elevated), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Movie, null, Modifier.size(22.dp), tint = AppTheme.colors.textMuted)
        }
        // Reads the shared value in the draw phase only: no recomposition per frame.
        else -> ShimmerBox(modifier.size(width, height))
    }
}

/** Center-crop of [img], then a 28 dp dark disc with the 16 dp play glyph, all in one draw. */
private fun Modifier.thumbnailImage(img: ImageBitmap): Modifier = drawWithCache {
    val scale = maxOf(size.width / img.width, size.height / img.height)
    val srcW = (size.width / scale).roundToInt().coerceIn(1, img.width)
    val srcH = (size.height / scale).roundToInt().coerceIn(1, img.height)
    val srcOffset = IntOffset((img.width - srcW) / 2, (img.height - srcH) / 2)
    val srcSize = IntSize(srcW, srcH)
    val dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
    val disc = 14.dp.toPx()
    val glyph = 16.dp.toPx()
    val glyphScale = glyph / 24f
    val glyphLeft = size.center.x - glyph / 2
    val glyphTop = size.center.y - glyph / 2
    onDrawBehind {
        drawImage(img, srcOffset, srcSize, dstSize = dstSize, filterQuality = FilterQuality.Low)
        drawCircle(PlayDisc, disc, center)
        translate(glyphLeft, glyphTop) {
            scale(glyphScale, glyphScale, pivot = Offset.Zero) { drawPath(PlayGlyph, Color.White) }
        }
    }
}
