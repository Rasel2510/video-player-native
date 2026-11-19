package com.ayesha.player.core.theme

import androidx.compose.ui.graphics.Color

data class AccentPreset(val label: String, val color: Color?)

/**
 * Port of `libraryAccentPresets` (library_appearance_provider.dart): folder icon and "NEW"
 * badge colors. Index 0 ("Theme") follows the current accent. The index is what gets
 * persisted, so append new presets at the end and never reorder.
 */
val LibraryAccentPresets = listOf(
    AccentPreset("Theme", null),
    AccentPreset("Coral", Color(0xFFFF6F61)),
    AccentPreset("Amber", Color(0xFFF5A623)),
    AccentPreset("Emerald", Color(0xFF10B981)),
    AccentPreset("Sky", Color(0xFF0EA5E9)),
    AccentPreset("Indigo", Color(0xFF6366F1)),
    AccentPreset("Rose", Color(0xFFF43F5E)),
    AccentPreset("Teal", Color(0xFF14B8A6)),
    AccentPreset("Orange", Color(0xFFF97316)),
    AccentPreset("Violet", Color(0xFF8B5CF6)),
    AccentPreset("Fuchsia", Color(0xFFD946EF)),
    AccentPreset("Cyan", Color(0xFF06B6D4)),
    AccentPreset("Slate", Color(0xFF64748B)),
)

fun resolveLibraryAccent(index: Int, themeDefault: Color): Color =
    LibraryAccentPresets[index.coerceIn(0, LibraryAccentPresets.lastIndex)].color ?: themeDefault

/** Folder/video card material (library_card_style_provider.dart). */
enum class LibraryCardStyle { Standard, Tinted }

/** Player control surface material (player_controls_style_provider.dart). */
enum class PlayerControlsStyle { Tint, Frosted }
