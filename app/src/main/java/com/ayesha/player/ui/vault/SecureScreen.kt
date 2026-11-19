package com.ayesha.player.ui.vault

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * secure_screen_guard.dart: FLAG_SECURE (no screenshots, blank in recents) while any vault
 * screen is up. Reference-counted, because the PIN screen and the vault list overlap during
 * the transition and a plain on/off from each would clear the flag too early.
 */
private object SecureScreenCount {
    var count = 0
}

@Composable
fun SecureScreen() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        SecureScreenCount.count++
        if (SecureScreenCount.count == 1) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (SecureScreenCount.count > 0) SecureScreenCount.count--
            if (SecureScreenCount.count == 0) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
