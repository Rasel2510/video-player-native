package com.ayesha.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ayesha.player.ui.common.EmphasizedAccelerate
import com.ayesha.player.ui.common.EmphasizedDecelerate
import com.ayesha.player.ui.common.HeroSource
import com.ayesha.player.ui.common.HeroSources
import com.ayesha.player.ui.navigation.Route
import kotlin.math.max
import kotlin.math.roundToInt

/** One open of the player; a new [id] builds a fresh player screen. */
@Immutable
class PlayerRequest(val route: Route.Player, val id: Long, val from: HeroSource?)

/** How the player is leaving. */
class PlayerExit(
    /** The last video frame, or null if none was drawn yet. */
    val frame: ImageBitmap?,
    /** Where that frame was on screen, in window pixels. */
    val videoBounds: Rect?,
    val path: String?,
    /** Fly back into the video's card. False for the mini player and audio mode. */
    val toCard: Boolean,
)

/**
 * The video player, drawn over the screens instead of being a navigation destination.
 *
 * As a destination it replaced the list it was opened from, so closing a video had to rebuild
 * that whole list in one frame (100-250 ms on a budget phone) right when the close animation
 * should run; it was mostly skipped. Over the list, the list stays as it was: the player can
 * grow out of the tapped thumbnail and shrink back into it with nothing else to do meanwhile.
 */
object PlayerOverlay {
    var request by mutableStateOf<PlayerRequest?>(null)
        private set

    internal var exit by mutableStateOf<PlayerExit?>(null)
        private set

    /** True while the player fully covers the screens: drawing them is skipped meanwhile. */
    var covering by mutableStateOf(false)
        internal set

    private var nextId = 0L

    fun open(route: Route.Player) {
        exit = null
        covering = false
        request = PlayerRequest(route, nextId++, HeroSources.find(route.path))
    }

    internal fun close(info: PlayerExit) {
        covering = false
        exit = info
    }

    internal fun finished(id: Long) {
        if (request?.id != id) return
        request = null
        exit = null
    }
}

/**
 * Runs [action] each time the player has finished closing: screens underneath refresh what
 * watching changed (resume points, Continue Watching), after the animation.
 */
@Composable
fun OnPlayerClosed(action: () -> Unit) {
    val latest by rememberUpdatedState(action)
    LaunchedEffect(Unit) {
        var wasOpen = PlayerOverlay.request != null
        snapshotFlow { PlayerOverlay.request != null }.collect { open ->
            if (wasOpen && !open) latest()
            wasOpen = open
        }
    }
}

/** True while the opening thumbnail is in flight: the poster underneath stays hidden meanwhile. */
internal val LocalHeroFlying = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

private const val OPEN_MS = 360
private const val CLOSE_TO_CARD_MS = 380
private const val CLOSE_MS = 260
private val ThumbCorner = 10.dp // AppRadius.sm, the cards' thumbnail corners

/** Place above the screens, below the mini player. */
@Composable
fun PlayerOverlayHost() {
    val req = PlayerOverlay.request ?: return
    key(req.id) { PlayerLayer(req) }
}

@Composable
private fun PlayerLayer(req: PlayerRequest) {
    val enter = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    val exit = PlayerOverlay.exit
    // The card to land on, looked up once when closing starts.
    var target by remember { mutableStateOf<HeroSource?>(null) }

    LaunchedEffect(Unit) {
        // Starts on the frame after the player's first composition, so that heavy frame
        // delays the motion a little instead of cutting into it.
        enter.animateTo(1f, tween(OPEN_MS, easing = EmphasizedDecelerate))
        if (PlayerOverlay.exit == null) PlayerOverlay.covering = true
    }
    LaunchedEffect(exit) {
        val e = exit ?: return@LaunchedEffect
        enter.snapTo(1f)
        target = if (e.toCard && e.path != null) HeroSources.find(e.path) else null
        if (target != null) {
            leave.animateTo(1f, tween(CLOSE_TO_CARD_MS, easing = EmphasizedDecelerate))
        } else {
            leave.animateTo(1f, tween(CLOSE_MS, easing = EmphasizedAccelerate))
        }
        PlayerOverlay.finished(req.id)
    }
    val flying = remember { derivedStateOf { req.from?.image != null && enter.value < 1f && PlayerOverlay.exit == null } }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // Hidden from the first closing frame: the flight layer below takes over, and a
                // SurfaceView can't fade with its screen anyway.
                if (PlayerOverlay.exit != null) {
                    alpha = 0f
                } else {
                    val t = enter.value
                    alpha = (t * 1.6f).coerceAtMost(1f)
                    val sc = 0.94f + 0.06f * t
                    scaleX = sc
                    scaleY = sc
                }
            },
        ) {
            CompositionLocalProvider(LocalHeroFlying provides flying) {
                PlayerScreen(req.route, onClose = PlayerOverlay::close)
            }
        }
        // The flying picture and, while closing, the fading backdrop. Drawn only: reading the
        // animations here redraws this one layer per frame and recomposes nothing.
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val clip = Path()
                onDrawBehind {
                    if (exit == null) {
                        drawOpening(req, enter.value, clip)
                    } else {
                        drawClosing(req, exit, leave.value, target, clip)
                    }
                }
            },
        )
    }
}

/** Card thumbnail -> the full-screen poster position (contain-fit, where LoadingPoster draws it). */
private fun DrawScope.drawOpening(req: PlayerRequest, t: Float, clip: Path) {
    if (t >= 1f) return
    val from = req.from ?: return
    val img = from.image ?: return
    val to = containRect(img.width.toFloat(), img.height.toFloat(), size)
    drawPicture(img, lerp(from.bounds, to, t), ThumbCorner.toPx() * (1f - t), 1f, clip)
}

private fun DrawScope.drawClosing(req: PlayerRequest, exit: PlayerExit, t: Float, target: HeroSource?, clip: Path) {
    // Stands in for the player's black background, which is hidden from the first closing frame.
    val scrim = 1f - (t / 0.7f).coerceIn(0f, 1f)
    if (scrim > 0f) drawRect(Color.Black, alpha = scrim)

    val picture = exit.frame ?: req.from?.image ?: return
    val start = exit.videoBounds ?: containRect(picture.width.toFloat(), picture.height.toFloat(), size)
    if (target != null) {
        // Back into its card: the frame shrinks onto the thumbnail, rounding its corners, and
        // crossfades into the thumbnail's own picture over the last part of the way.
        val r = lerp(start, target.bounds, t)
        val corner = ThumbCorner.toPx() * t
        drawPicture(picture, r, corner, 1f, clip)
        val thumb = target.image
        val a = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (thumb != null && a > 0f) drawPicture(thumb, r, corner, a, clip)
    } else {
        // Nowhere to land (mini player, audio mode, or the card scrolled away): shrink and fade.
        val s = 1f - 0.08f * t
        val c = start.center
        val r = Rect(c.x - start.width * s / 2, c.y - start.height * s / 2, c.x + start.width * s / 2, c.y + start.height * s / 2)
        drawPicture(picture, r, 0f, 1f - t, clip)
    }
}

/** [img] center-cropped into [r], with rounded corners. */
private fun DrawScope.drawPicture(img: ImageBitmap, r: Rect, corner: Float, alpha: Float, clip: Path) {
    if (r.width < 1f || r.height < 1f || alpha <= 0f) return
    val iw = img.width.toFloat()
    val ih = img.height.toFloat()
    val scale = max(r.width / iw, r.height / ih)
    val sw = (r.width / scale).coerceAtMost(iw)
    val sh = (r.height / scale).coerceAtMost(ih)
    val srcOffset = IntOffset(((iw - sw) / 2).roundToInt(), ((ih - sh) / 2).roundToInt())
    val srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1))
    val dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt())
    val dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1))
    if (corner > 0.5f) {
        clip.reset()
        clip.addRoundRect(RoundRect(r, CornerRadius(corner)))
        clipPath(clip) { drawImage(img, srcOffset, srcSize, dstOffset, dstSize, alpha = alpha) }
    } else {
        drawImage(img, srcOffset, srcSize, dstOffset, dstSize, alpha = alpha)
    }
}

private fun containRect(w: Float, h: Float, box: Size): Rect {
    if (w <= 0f || h <= 0f) return Rect(0f, 0f, box.width, box.height)
    val scale = minOf(box.width / w, box.height / h)
    val dw = w * scale
    val dh = h * scale
    val left = (box.width - dw) / 2
    val top = (box.height - dh) / 2
    return Rect(left, top, left + dw, top + dh)
}
