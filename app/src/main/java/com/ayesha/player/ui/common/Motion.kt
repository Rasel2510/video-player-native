package com.ayesha.player.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/** Material 3 emphasized curves: fast start, long gentle settle (and its exit mirror). */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * One field of a frequently changing state object. The caller recomposes only when the picked
 * value changes, not on every update of [this] (the player's position ticks several times a
 * second; without this the whole player screen redrew with it).
 *
 * [selector] must only read its argument: it is captured once.
 */
@Composable
fun <S, T> State<S>.pick(selector: (S) -> T): T {
    val source = this
    return remember(source) { derivedStateOf { selector(source.value) } }.value
}

/**
 * AnimatedVisibility with a fade that multiplies each draw's alpha instead of rendering the
 * content into an offscreen layer first. Same look for overlays like the player controls, but
 * no full-screen offscreen buffer per frame on a budget GPU.
 */
@Composable
fun Fade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    inMs: Int = 180,
    outMs: Int = 220,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(visible, modifier, enter = EnterTransition.None, exit = ExitTransition.None) {
        val scope = this
        // Part of this AnimatedVisibility's transition, so the content stays until it finishes.
        val alpha by transition.animateFloat(
            transitionSpec = { tween(if (targetState == EnterExitState.Visible) inMs else outMs, easing = LinearEasing) },
            label = "fade",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        Box(
            Modifier.graphicsLayer {
                this.alpha = alpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        ) {
            scope.content()
        }
    }
}
