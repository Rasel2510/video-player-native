package com.ayesha.player.core.util

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Flutter's `Color.lerp`: per-channel linear interpolation in 8-bit sRGB.
 * Compose's `lerp(Color, Color, t)` blends in Oklab instead, which gives visibly different
 * mid-points, so anything ported from a Dart `Color.lerp` must use this.
 */
fun srgbLerp(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    fun ch(x: Float, y: Float): Int = ((x * 255f) + ((y * 255f) - (x * 255f)) * f).roundToInt().coerceIn(0, 255)
    return Color(
        red = ch(a.red, b.red),
        green = ch(a.green, b.green),
        blue = ch(a.blue, b.blue),
        alpha = ch(a.alpha, b.alpha),
    )
}

/** Port of volume_color.dart: gauge colors for 0-200 % volume (boost above 100 %). */
object VolumeColor {
    val boost = Color(0xFFFF8C00)
    val boostSoft = Color(0x33FF8C00)
    val boostGradEnd = Color(0xFFFF3B30)

    /** 0 at or below 100 %, 1 at 200 %. */
    fun boostT(volumePercent: Float): Float = ((volumePercent - 100f) / 100f).coerceIn(0f, 1f)

    /** Bar fill: accent up to 100 %, heating toward red across the boost range. */
    fun barColor(volumePercent: Float, accent: Color): Color {
        val t = boostT(volumePercent)
        return if (t <= 0f) accent else srgbLerp(accent, boostGradEnd, t)
    }

    /** Gauge color: accent up to 100 %, toward orange across the boost range. */
    fun forVolume(volumePercent: Float, accent: Color): Color {
        val t = boostT(volumePercent)
        return if (t <= 0f) accent else srgbLerp(accent, boost, t)
    }
}
