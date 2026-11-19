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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
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

/**
 * thumbnail_widget.dart: async thumbnail with a shimmer while loading, a play glyph and the
 * duration chip once loaded, and a movie icon if it can't be generated. Reloads in place
 * when a thumbnail arrives later (e.g. captured by the playing software engine).
 */
/** Resolved once for the whole app (it used to be looked up again in every list row). */
private var thumbnailRepo: ThumbnailRepository? = null

private fun thumbnails(context: android.content.Context): ThumbnailRepository =
    thumbnailRepo ?: EntryPointAccessors.fromApplication(context.applicationContext, ThumbnailEntryPoint::class.java)
        .thumbnails().also { thumbnailRepo = it }

@Composable
fun VideoThumbnail(videoPath: String, width: Dp, height: Dp, duration: Duration?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = thumbnails(context)
    var file by remember(videoPath) { mutableStateOf(repo.peek(videoPath)) }
    var failed by remember(videoPath) { mutableStateOf(false) }

    // Only rows without a thumbnail yet start any work. Already-loaded rows (most of them while
    // scrolling a folder you have seen before) launch nothing at all.
    if (file == null) {
        LaunchedEffect(videoPath) {
            val got = repo.getThumbnail(videoPath)
            if (got != null) {
                file = got
                return@LaunchedEffect
            }
            failed = true
            // It may still arrive later (e.g. captured by the playing software engine).
            repo.updates.first { it == videoPath }
            file = repo.getThumbnail(videoPath)
            failed = file == null
        }
    }

    // Exact pixel size up front: Coil can then answer from its memory cache synchronously, so a
    // cached thumbnail is there on the row's first frame instead of popping in a frame later.
    val density = LocalDensity.current
    val pxW = with(density) { width.roundToPx() }
    val pxH = with(density) { height.roundToPx() }

    Box(modifier.size(width, height)) {
        val f: File? = file
        when {
            f != null -> {
                val request = remember(f, pxW, pxH) {
                    ImageRequest.Builder(context).data(f).size(pxW, pxH).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.align(Alignment.Center).size(28.dp).background(Color(0x80000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp), tint = Color.White)
                }
                if (duration != null && duration.inWholeSeconds > 0) {
                    Text(
                        DurationFormatter.format(duration),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.W600, letterSpacing = 0.2.sp),
                    )
                }
            }
            failed -> Box(Modifier.fillMaxSize().background(AppTheme.colors.elevated), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Movie, null, Modifier.size(22.dp), tint = AppTheme.colors.textMuted)
            }
            // Reads the shared value in the draw phase only: no recomposition per frame.
            else -> ShimmerBox(Modifier.fillMaxSize())
        }
    }
}
