package com.ayesha.player.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Port of `AppThemeTextStyles`. Flutter letterSpacing is in logical px, so it maps to sp. */
@Immutable
data class AppTextStyles(
    val label: TextStyle,
    val labelAccent: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
)

fun appTextStyles(c: AppColors) = AppTextStyles(
    label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.W600, letterSpacing = 0.8.sp, color = c.textSecondary),
    labelAccent = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.W700, letterSpacing = 0.5.sp, color = c.accent),
    body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W400, color = c.textPrimary, lineHeight = 1.4.em),
    bodySmall = TextStyle(fontSize = 12.sp, color = c.textSecondary, lineHeight = 1.3.em),
    caption = TextStyle(fontSize = 11.sp, color = c.textMuted, letterSpacing = 0.2.sp),
    mono = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.textSecondary, letterSpacing = 0.5.sp),
)

val LocalAppTextStyles = staticCompositionLocalOf { appTextStyles(DarkAppColors) }

/** Port of `AppRadius`. */
object AppRadius {
    val xs = RoundedCornerShape(6.dp)
    val sm = RoundedCornerShape(10.dp)
    val md = RoundedCornerShape(14.dp)
    val lg = RoundedCornerShape(20.dp)
    val xl = RoundedCornerShape(28.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}
