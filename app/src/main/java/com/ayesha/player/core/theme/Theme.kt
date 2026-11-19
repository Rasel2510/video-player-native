package com.ayesha.player.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/** Mirrors Flutter's ThemeMode (persisted by index in the Flutter build: system, light, dark). */
enum class ThemeMode { System, Light, Dark }

@Composable
fun AyeshaTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) DarkAppColors else LightAppColors
    val textStyles = remember(colors) { appTextStyles(colors) }

    // Material components (sliders, dialogs, text buttons, progress) read these slots.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.bg,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainerHighest = colors.panel,
            surfaceContainerHigh = colors.panel,
            surfaceContainer = colors.panel,
            outline = colors.border,
            outlineVariant = colors.divider,
            error = colors.errorRed,
            errorContainer = colors.errorBg,
            surfaceTint = Color.Transparent,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.bg,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainerHighest = colors.panel,
            surfaceContainerHigh = colors.panel,
            surfaceContainer = colors.panel,
            outline = colors.border,
            outlineVariant = colors.divider,
            error = colors.errorRed,
            errorContainer = colors.errorBg,
            surfaceTint = Color.Transparent,
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalAppTextStyles provides textStyles,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(small = AppRadius.sm, medium = AppRadius.md, large = AppRadius.lg),
            content = content,
        )
    }
}

/** Equivalent of Flutter's `context.colors` / `context.textStyles`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val text: AppTextStyles
        @Composable @ReadOnlyComposable get() = LocalAppTextStyles.current
}
