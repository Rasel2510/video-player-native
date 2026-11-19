package com.ayesha.player.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.ayesha.player.core.theme.LibraryAccentPresets
import com.ayesha.player.core.theme.LibraryCardStyle
import com.ayesha.player.core.theme.PlayerControlsStyle
import com.ayesha.player.core.theme.ThemeMode
import com.ayesha.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val folderIconColorIndex: Int = 0,
    val newBadgeColorIndex: Int = 0,
    val cardStyle: LibraryCardStyle = LibraryCardStyle.Standard,
    val controlsStyle: PlayerControlsStyle = PlayerControlsStyle.Tint,
)

/**
 * Theme and library/player appearance settings. Replaces theme_provider, library_appearance_provider,
 * library_card_style_provider and player_controls_style_provider plus their parts of
 * player_preferences_service.dart.
 *
 * Key names and value encodings match the Flutter build (minus Flutter's `flutter.` prefix),
 * so a later data migration is a straight copy.
 */
@Singleton
class AppearancePreferences @Inject constructor(
    private val store: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private object Keys {
        val themeMode = intPreferencesKey("app_theme_mode_v1")
        val folderIconColor = intPreferencesKey("folder_icon_color_index_v1")
        val newBadgeColor = intPreferencesKey("new_badge_color_index_v1")
        val cardTinted = booleanPreferencesKey("library_card_tinted_v1")
        val controlsFrosted = booleanPreferencesKey("player_controls_frosted_v1")
    }

    /**
     * Null until the first read completes. MainActivity keeps the splash screen up while it is
     * null, so the saved theme applies on the first frame (Flutter's `preload()` in main.dart).
     */
    val settings: StateFlow<AppearanceSettings?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            val lastAccent = LibraryAccentPresets.lastIndex
            AppearanceSettings(
                themeMode = ThemeMode.entries.getOrElse(p[Keys.themeMode] ?: 0) { ThemeMode.System },
                folderIconColorIndex = (p[Keys.folderIconColor] ?: 0).coerceIn(0, lastAccent),
                newBadgeColorIndex = (p[Keys.newBadgeColor] ?: 0).coerceIn(0, lastAccent),
                cardStyle = if (p[Keys.cardTinted] == true) LibraryCardStyle.Tinted else LibraryCardStyle.Standard,
                controlsStyle = if (p[Keys.controlsFrosted] == true) PlayerControlsStyle.Frosted else PlayerControlsStyle.Tint,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun setThemeMode(mode: ThemeMode) = save { it[Keys.themeMode] = mode.ordinal }

    /** Same cycle as ThemeNotifier.toggleTheme: dark -> light -> system -> dark. */
    fun toggleThemeMode() {
        val next = when (settings.value?.themeMode ?: ThemeMode.System) {
            ThemeMode.Dark -> ThemeMode.Light
            ThemeMode.Light -> ThemeMode.System
            ThemeMode.System -> ThemeMode.Dark
        }
        setThemeMode(next)
    }

    fun setFolderIconColorIndex(index: Int) = save { it[Keys.folderIconColor] = index }
    fun setNewBadgeColorIndex(index: Int) = save { it[Keys.newBadgeColor] = index }
    fun setCardStyle(style: LibraryCardStyle) = save { it[Keys.cardTinted] = style == LibraryCardStyle.Tinted }
    fun setControlsStyle(style: PlayerControlsStyle) =
        save { it[Keys.controlsFrosted] = style == PlayerControlsStyle.Frosted }

    // Fire-and-forget like the Dart saves, but on the app scope so a write isn't
    // cancelled when the screen that triggered it goes away.
    private fun save(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch {
            try {
                store.edit { block(it) }
            } catch (_: IOException) {
                // Same as the Dart `catch (_) {}`: a failed preference write is not fatal.
            }
        }
    }
}
