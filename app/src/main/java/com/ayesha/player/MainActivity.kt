package com.ayesha.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayesha.player.core.theme.AyeshaTheme
import com.ayesha.player.data.intent.OpenFileEvents
import com.ayesha.player.data.prefs.AppearancePreferences
import com.ayesha.player.data.prefs.AppearanceSettings
import com.ayesha.player.playback.PlayerController
import com.ayesha.player.ui.common.LocalAppearance
import com.ayesha.player.ui.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
/** A FragmentActivity (a ComponentActivity) because the vault's BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {

    @Inject lateinit var openFileEvents: OpenFileEvents
    @Inject lateinit var appearancePreferences: AppearancePreferences
    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var library: com.ayesha.player.data.library.LibraryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until saved appearance settings are read, so the first frame
        // already uses the saved theme (the Flutter build awaited preload() for the same reason).
        // Also read the saved library behind the splash (at most 0.7 s), so the first frame already
        // shows the folders instead of an empty screen, as the Flutter build did.
        val canRead = com.ayesha.player.data.device.StoragePermissions.canReadLibrary(this)
        if (canRead) library.warmUp()
        val splashDeadline = android.os.SystemClock.elapsedRealtime() + 700
        splash.setKeepOnScreenCondition {
            appearancePreferences.settings.value == null ||
                (canRead && !library.state.value.hasLoaded && android.os.SystemClock.elapsedRealtime() < splashDeadline)
        }
        // Edge-to-edge from the first frame, like SystemUiMode.edgeToEdge in main.dart.
        enableEdgeToEdge()
        if (savedInstanceState == null) openFileEvents.handle(intent, contentResolver)

        // Build the ExoPlayer ahead of time, once launch has settled and the main thread is idle, so
        // opening the first video doesn't construct one in the middle of the open animation.
        window.decorView.postDelayed({
            android.os.Looper.myQueue().addIdleHandler {
                playerController.prewarm()
                false
            }
        }, 1500)

        setContent {
            val settings by appearancePreferences.settings.collectAsStateWithLifecycle()
            val current = settings ?: AppearanceSettings()
            val dark = when (current.themeMode) {
                com.ayesha.player.core.theme.ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
                com.ayesha.player.core.theme.ThemeMode.Light -> false
                com.ayesha.player.core.theme.ThemeMode.Dark -> true
            }
            // Status / nav bar icon color from the APP theme, not the phone's: with the app in light
            // mode on a dark-mode phone, the icons were white on white (an "invisible" status bar).
            androidx.compose.runtime.DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) androidx.activity.SystemBarStyle.dark(transparent)
                else androidx.activity.SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AyeshaTheme(themeMode = current.themeMode) {
                CompositionLocalProvider(LocalAppearance provides current) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Files may change while we're away: the library reloads when it's next shown.
        if (!isChangingConfigurations) library.markStale()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFileEvents.handle(intent, contentResolver)
    }

    /** Floating window at the video's aspect ratio; Android only accepts ~0.42..2.39, else 16:9. */
    fun enterPip(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val ratio = w.toFloat() / h
            val rational = if (ratio < 0.42f || ratio > 2.39f) Rational(16, 9) else Rational(w, h)
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(rational).build())
        } catch (_: Exception) {
            // PiP unsupported or disabled in system settings.
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerController.setPipMode(isInPictureInPictureMode)
    }
}
