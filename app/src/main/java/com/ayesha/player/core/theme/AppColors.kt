package com.ayesha.player.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Port of `AppThemeColors` (lib/core/theme/app_theme.dart). Values must stay identical. */
@Immutable
data class AppColors(
    val bg: Color,
    val surface: Color,
    val panel: Color,
    val elevated: Color,
    val border: Color,
    val divider: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentGlow: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDim: Color,
    val folderTint: Color,
    val folderIcon: Color,
    val errorRed: Color,
    val errorBg: Color,
    val progressBg: Color,
    val progressFill: Color,
    val isDark: Boolean,
)

val DarkAppColors = AppColors(
    bg = Color(0xFF0C0C0E),
    surface = Color(0xFF141416),
    panel = Color(0xFF1C1C1F),
    elevated = Color(0xFF242428),
    border = Color(0xFF2C2C30),
    divider = Color(0xFF1F1F23),
    accent = Color(0xFFFF8C00),
    accentSoft = Color(0x33FF8C00),
    accentGlow = Color(0x44FF8C00),
    textPrimary = Color(0xFFF0F0F2),
    textSecondary = Color(0xFF8A8A92),
    textMuted = Color(0xFF4A4A52),
    textDim = Color(0xFF2E2E34),
    folderTint = Color(0xFF2A1020),
    folderIcon = Color(0xFFFF6B8E),
    errorRed = Color(0xFFFF5C5C),
    errorBg = Color(0xFF2A1010),
    progressBg = Color(0xFF252528),
    progressFill = Color(0xFFFF8C00),
    isDark = true,
)

val LightAppColors = AppColors(
    bg = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    panel = Color(0xFFF0F0F3),
    elevated = Color(0xFFEAEAED),
    border = Color(0xFFDCDCE0),
    divider = Color(0xFFE5E5E8),
    accent = Color(0xFFE07000),
    accentSoft = Color(0x33E07000),
    accentGlow = Color(0x40E07000),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF6B6B70),
    textMuted = Color(0xFF909096),
    textDim = Color(0xFFB0B0B6),
    folderTint = Color(0xFFFFE5EC),
    folderIcon = Color(0xFFE8436B),
    errorRed = Color(0xFFE53935),
    errorBg = Color(0xFFFFEBEE),
    progressBg = Color(0xFFE0E0E0),
    progressFill = Color(0xFFE07000),
    isDark = false,
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }
