package com.ayesha.player.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.ayesha.player.ui.common.EmphasizedAccelerate
import com.ayesha.player.ui.common.EmphasizedDecelerate
import com.ayesha.player.ui.common.pick
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.ui.common.ShimmerScope
import com.ayesha.player.ui.common.LocalNavAnimatedScope
import com.ayesha.player.ui.common.LocalSharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import com.ayesha.player.ui.folder.FolderVideosScreen
import com.ayesha.player.ui.library.HomeScreen
import com.ayesha.player.ui.picker.FolderPickerScreen
import com.ayesha.player.ui.vault.VaultPinScreen
import com.ayesha.player.ui.vault.VaultScreen
import com.ayesha.player.ui.player.MiniPlayerOverlay
import com.ayesha.player.ui.player.MiniPlayerViewModel
import com.ayesha.player.ui.player.PlayerScreen
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// smooth_page_route.dart: the new screen slides in from the right edge over the old one, which
// drifts 30 % to the left (parallax); back reverses it. Curves.fastOutSlowIn both ways, 380 ms
// in and 350 ms back. No fades: two opaque layers moving is the cheapest transition there is.
private const val PUSH_MS = 380
private const val POP_MS = 350
private val FastOutSlowIn = androidx.compose.animation.core.FastOutSlowInEasing

private fun navEnter(forward: Boolean) =
    if (forward) {
        slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowIn)) { w -> w }
    } else {
        slideInHorizontally(tween(POP_MS, easing = FastOutSlowIn)) { w -> -(w * 0.3f).toInt() }
    }

private fun navExit(forward: Boolean) =
    if (forward) {
        slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowIn)) { w -> -(w * 0.3f).toInt() }
    } else {
        slideOutHorizontally(tween(POP_MS, easing = FastOutSlowIn)) { w -> w }
    }

/**
 * A screen in the stack. The soft shadow along its left edge (the route's BoxShadow) separates
 * it from the screen underneath while it slides; at rest it lies off-screen. One gradient rect.
 */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val w = 24.dp.toPx()
            val shadow = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.55f to Color(0x14000000),
                1f to Color(0x33000000),
                startX = -w,
                endX = 0f,
            )
            onDrawBehind { drawRect(shadow, topLeft = Offset(-w, 0f), size = Size(w, size.height)) }
        },
    ) { content() }
}

// The player opens like a zoom into the video: it grows from 94 % while fading in, over a list
// that stays put (only one layer animates, and the thumbnail Hero flies on top). Closing reverses
// it: the frozen frame shrinks back a little while fading out over the list.
private val PlayerEnter = fadeIn(tween(220, easing = LinearEasing)) +
    scaleIn(tween(340, easing = EmphasizedDecelerate), initialScale = 0.94f)
private val PlayerExit = fadeOut(tween(200, delayMillis = 40, easing = LinearEasing)) +
    scaleOut(tween(260, easing = EmphasizedAccelerate), targetScale = 0.94f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.toPlayer() = targetState.destination.hasRoute<Route.Player>()
private fun AnimatedContentTransitionScope<NavBackStackEntry>.fromPlayer() = initialState.destination.hasRoute<Route.Player>()

/**
 * Root of the UI. The mini player is drawn in this Box above the NavHost, the same way
 * MiniPlayerOverlay wraps the whole MaterialApp in app.dart.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val activity = LocalContext.current as? Activity
    val mini: MiniPlayerViewModel = hiltViewModel()
    // Only this flag, not the whole player state: the root must not recompose on position ticks.
    val keepAlive = mini.controller.state.collectAsStateWithLifecycle()
        .pick { it.isActive && (it.audioMode || it.isMiniPlayerActive) }

    // Registered before the NavHost, so it only fires when there's nothing left to pop (home).
    // While audio mode or the mini player is playing, go to the background instead of
    // finishing, so playback and its notification continue (home_screen.dart's PopScope).
    BackHandler(enabled = keepAlive) {
        activity?.moveTaskToBack(true)
    }

    Box(Modifier.fillMaxSize().background(AppTheme.colors.bg)) {
        ShimmerScope {
          // Shared elements: the video card thumbnail -> player poster "Hero" (see sharedVideoThumb).
          SharedTransitionLayout {
           CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = nav,
                startDestination = Route.Home,
                enterTransition = { navEnter(forward = true) },
                // Under the player the screen holds still: the player animates over it.
                exitTransition = { if (toPlayer()) ExitTransition.KeepUntilTransitionsFinished else navExit(forward = true) },
                popEnterTransition = { if (fromPlayer()) EnterTransition.None else navEnter(forward = false) },
                popExitTransition = { navExit(forward = false) },
            ) {
                composable<Route.Home> { Page {
                    HomeScreen(
                        onOpenPlayer = { nav.navigate(it) },
                        onOpenFolder = { nav.navigate(Route.FolderVideos(it)) },
                        onOpenVault = { nav.navigate(it) },
                    )
                }
                }
                composable<Route.VaultPin> { Page {
                    VaultPinScreen(
                        onCancel = { nav.popBackStack() },
                        onSuccess = { pin ->
                            when (pin.next) {
                                // Unlocked (or first PIN created): replace the PIN pad with the vault.
                                "open" -> nav.navigate(Route.Vault) { popUpTo<Route.VaultPin> { inclusive = true } }
                                // Change PIN, step 1 verified: now create the new one.
                                "change" -> nav.navigate(Route.VaultPin("create", "changed")) { popUpTo<Route.VaultPin> { inclusive = true } }
                                // Change PIN, step 2 saved: back to the vault, which says so.
                                else -> {
                                    nav.previousBackStackEntry?.savedStateHandle?.set(Route.PIN_CHANGED_KEY, true)
                                    nav.popBackStack()
                                }
                            }
                        },
                    )
                }
                }
                composable<Route.Vault> { entry -> Page {
                    val pinChanged by entry.savedStateHandle
                        .getStateFlow(Route.PIN_CHANGED_KEY, false)
                        .collectAsStateWithLifecycle()
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    VaultScreen(
                        onBack = { nav.popBackStack() },
                        onOpenPlayer = { nav.navigate(it) },
                        onChangePin = { nav.navigate(Route.VaultPin("unlock", "change")) },
                        pinChanged = pinChanged,
                        onPinChangedConsumed = { entry.savedStateHandle[Route.PIN_CHANGED_KEY] = false },
                    )
                    }
                }
                }
                composable<Route.FolderVideos> { entry -> Page {
                    val picked by entry.savedStateHandle
                        .getStateFlow<String?>(Route.PICKED_FOLDER_KEY, null)
                        .collectAsStateWithLifecycle()
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    FolderVideosScreen(
                        onBack = { nav.popBackStack() },
                        onOpenPlayer = { nav.navigate(it) },
                        onPickFolder = { nav.navigate(it) },
                        pickedFolder = picked,
                        onPickedConsumed = { entry.savedStateHandle[Route.PICKED_FOLDER_KEY] = null },
                    )
                    }
                }
                }
                composable<Route.FolderPicker> { Page {
                    FolderPickerScreen(
                        onBack = { nav.popBackStack() },
                        onPicked = { path ->
                            nav.previousBackStackEntry?.savedStateHandle?.set(Route.PICKED_FOLDER_KEY, path)
                            nav.popBackStack()
                        },
                    )
                }
                }
                composable<Route.Player>(
                    enterTransition = { PlayerEnter },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(200)) },
                    popExitTransition = { PlayerExit },
                ) {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                        PlayerScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
           }
          }
        }
        MiniPlayerOverlay(onExpand = { nav.navigate(it) }, vm = mini)
    }
}
