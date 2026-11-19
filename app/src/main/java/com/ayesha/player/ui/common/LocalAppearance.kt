package com.ayesha.player.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.ayesha.player.data.prefs.AppearanceSettings

/**
 * Current appearance settings (card style, badge and folder colors), provided once by
 * MainActivity so cards and badges don't each collect the preferences flow.
 */
val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }
