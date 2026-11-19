package com.ayesha.player.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ayesha.player.data.prefs.PlaybackSettings

/** subtitle_style_provider.dart. Indices are persisted: append presets, never insert. */
val SubtitleColorPresets = listOf(
    Color(0xFFFFFFFF), Color(0xFFFFEB3B), Color(0xFF00E5FF), Color(0xFF76FF03),
    Color(0xFFFF4081), Color(0xFFFF9800), Color(0xFFE040FB),
    Color(0xFFFFD54F), Color(0xFF64B5F6), Color(0xFF69F0AE), Color(0xFFFF8A65),
    Color(0xFFB388FF), Color(0xFFF48FB1),
)

val SubtitleBgColorPresets = listOf(
    Color(0xAA000000), Color(0xAA2196F3), Color(0xAA4CAF50), Color(0xAAF44336),
    Color(0xAA9C27B0), Color(0xAA37474F), Color(0xAA1A237E), Color(0xAA2E7D32),
)

/** Android's built-in families, so nothing is bundled. Null = default. */
data class SubtitleFont(val label: String, val family: FontFamily?)

val SubtitleFontPresets = listOf(
    SubtitleFont("Default", null),
    SubtitleFont("Serif", FontFamily.Serif),
    SubtitleFont("Mono", FontFamily.Monospace),
    SubtitleFont("Condensed", FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))),
    SubtitleFont("Light", FontFamily(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL))),
    SubtitleFont("Cursive", FontFamily.Cursive),
    SubtitleFont("Casual", FontFamily(android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL))),
    SubtitleFont("Small Caps", FontFamily(android.graphics.Typeface.create("sans-serif-smallcaps", android.graphics.Typeface.NORMAL))),
)

@Immutable
data class SubtitleStyle(
    val fontSize: Float = 32f,
    val colorIndex: Int = 0,
    val background: Boolean = true,
    val backgroundColorIndex: Int = 0,
    val fontIndex: Int = 0,
    /** Drag offset from the default position, in dp; null = default. */
    val offsetX: Dp? = null,
    val offsetY: Dp? = null,
) {
    val color: Color get() = SubtitleColorPresets[colorIndex.coerceIn(0, SubtitleColorPresets.lastIndex)]
    val backgroundColor: Color get() = SubtitleBgColorPresets[backgroundColorIndex.coerceIn(0, SubtitleBgColorPresets.lastIndex)]
    val fontFamily: FontFamily? get() = SubtitleFontPresets[fontIndex.coerceIn(0, SubtitleFontPresets.lastIndex)].family

    companion object {
        const val MIN_FONT = 16f
        const val MAX_FONT = 56f

        fun from(s: PlaybackSettings?): SubtitleStyle {
            if (s == null) return SubtitleStyle()
            return SubtitleStyle(
                fontSize = s.subtitleFontSize,
                colorIndex = s.subtitleColorIndex,
                background = s.subtitleBackground,
                backgroundColorIndex = s.subtitleBgColorIndex,
                fontIndex = s.subtitleFontIndex,
                offsetX = s.subtitlePositionX?.dp,
                offsetY = s.subtitlePositionY?.dp,
            )
        }
    }
}
