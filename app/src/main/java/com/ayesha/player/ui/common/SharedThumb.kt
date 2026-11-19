package com.ayesha.player.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** The NavHost's shared-transition scope (provided once, around the NavHost). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The current destination's enter/exit animation scope (provided per destination). */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The one video whose thumbnail takes part in the next card -> player animation. Set right
 * before opening a video. Only this thumbnail is registered as a shared element: registering
 * every thumbnail in a list made Compose track all of their positions on every scroll frame,
 * which was a large part of the video list's scroll lag.
 */
object HeroTarget {
    var path: String? by mutableStateOf(null)
}

/**
 * Flutter's `Hero(tag: 'video_thumb_<path>')`: the tapped card's thumbnail grows into the
 * player's full-screen loading poster. A no-op for every other thumbnail, and outside the NavHost.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedVideoThumb(path: String): Modifier {
    // derivedStateOf: tapping a card recomposes only the old and the new target, not every row.
    val active by remember(path) { derivedStateOf { HeroTarget.path == path } }
    if (!active) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedVideoThumb.sharedElement(rememberSharedContentState("video_thumb_$path"), animated)
    }
}
