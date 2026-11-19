package com.ayesha.player.ui.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.ui.common.ShimmerScope
import com.ayesha.player.ui.common.pick
import com.ayesha.player.ui.folder.FolderVideosScreen
import com.ayesha.player.ui.library.HomeScreen
import com.ayesha.player.ui.picker.FolderPickerScreen
import com.ayesha.player.ui.player.MiniPlayerOverlay
import com.ayesha.player.ui.player.MiniPlayerViewModel
import com.ayesha.player.ui.player.PlayerOverlay
import com.ayesha.player.ui.player.PlayerOverlayHost
import com.ayesha.player.ui.vault.VaultPinScreen
import com.ayesha.player.ui.vault.VaultScreen

// smooth_page_route.dart: the new screen slides in from the right edge over the old one, which
// drifts 30 % to the left (parallax); back reverses it. Curves.fastOutSlowIn both ways, 380 ms
// in and 350 ms back. No fades: two opaque layers moving is the cheapest transition there is.
private const val PUSH_MS = 380
private const val POP_MS = 350

private fun navEnter(forward: Boolean) =
    if (forward) {
        slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { w -> w }
    } else {
        slideInHorizontally(tween(POP_MS, easing = FastOutSlowInEasing)) { w -> -(w * 0.3f).toInt() }
    }

private fun navExit(forward: Boolean) =
    if (forward) {
        slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { w -> -(w * 0.3f).toInt() }
    } else {
        slideOutHorizontally(tween(POP_MS, easing = FastOutSlowInEasing)) { w -> w }
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

/**
 * Root of the UI: the screens, the video player over them (see [PlayerOverlay]), and the mini
 * player over everything, the same way MiniPlayerOverlay wraps the whole MaterialApp in app.dart.
 */
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

    val openPlayer: (Route.Player) -> Unit = { PlayerOverlay.open(it) }

    Box(Modifier.fillMaxSize().background(AppTheme.colors.bg)) {
        ShimmerScope {
            NavHost(
                navController = nav,
                startDestination = Route.Home,
                // While the player covers the screen, drawing the screens under it is wasted work.
                modifier = Modifier.drawWithContent { if (!PlayerOverlay.covering) drawContent() },
                enterTransition = { navEnter(forward = true) },
                exitTransition = { navExit(forward = true) },
                popEnterTransition = { navEnter(forward = false) },
                popExitTransition = { navExit(forward = false) },
            ) {
                composable<Route.Home> {
                    Page {
                        HomeScreen(
                            onOpenPlayer = openPlayer,
                            onOpenFolder = { nav.navigate(Route.FolderVideos(it)) },
                            onOpenVault = { nav.navigate(it) },
                        )
                    }
                }
                composable<Route.VaultPin> {
                    Page {
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
                composable<Route.Vault> { entry ->
                    val pinChanged by entry.savedStateHandle
                        .getStateFlow(Route.PIN_CHANGED_KEY, false)
                        .collectAsStateWithLifecycle()
                    Page {
                        VaultScreen(
                            onBack = { nav.popBackStack() },
                            onOpenPlayer = openPlayer,
                            onChangePin = { nav.navigate(Route.VaultPin("unlock", "change")) },
                            pinChanged = pinChanged,
                            onPinChangedConsumed = { entry.savedStateHandle[Route.PIN_CHANGED_KEY] = false },
                        )
                    }
                }
                composable<Route.FolderVideos> { entry ->
                    val picked by entry.savedStateHandle
                        .getStateFlow<String?>(Route.PICKED_FOLDER_KEY, null)
                        .collectAsStateWithLifecycle()
                    Page {
                        FolderVideosScreen(
                            onBack = { nav.popBackStack() },
                            onOpenPlayer = openPlayer,
                            onPickFolder = { nav.navigate(it) },
                            pickedFolder = picked,
                            onPickedConsumed = { entry.savedStateHandle[Route.PICKED_FOLDER_KEY] = null },
                        )
                    }
                }
                composable<Route.FolderPicker> {
                    Page {
                        FolderPickerScreen(
                            onBack = { nav.popBackStack() },
                            onPicked = { path ->
                                nav.previousBackStackEntry?.savedStateHandle?.set(Route.PICKED_FOLDER_KEY, path)
                                nav.popBackStack()
                            },
                        )
                    }
                }
            }
            // Over the screens, so the one it was opened from stays alive underneath.
            PlayerOverlayHost()
        }
        MiniPlayerOverlay(onExpand = openPlayer, vm = mini)
    }
}
