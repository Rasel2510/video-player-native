package com.ayesha.player.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.ayesha.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How the library finds videos (scan_mode_provider.dart). Persisted by ordinal. */
enum class LibraryScanMode(val label: String, val description: String) {
    Hybrid("Hybrid", "Fast, live updates, and finds WhatsApp/Telegram videos. Recommended."),
    MediaStore("MediaStore", "Fastest - new videos appear instantly, but WhatsApp/.nomedia videos won't show."),
    FileScanner("File scanner", "Scans every folder, incl. WhatsApp. Complete but slower, no live updates."),
}

/**
 * Everything else from player_preferences_service.dart and brightness_service.dart.
 * Fit/sort/loop stay raw indices here; their enums arrive with the screens that own them
 * (phases 3-4) and must keep the Flutter ordinals.
 */
data class PlaybackSettings(
    val fitModeIndex: Int = 0,
    val speed: Float = 1f,
    val sortByIndex: Int = 0,
    val loopModeIndex: Int = 0,
    val scanMode: LibraryScanMode = LibraryScanMode.Hybrid,
    val subtitleFontSize: Float = 32f,
    val subtitleColorIndex: Int = 0,
    val subtitleBackground: Boolean = true,
    val subtitleBgColorIndex: Int = 0,
    val subtitleFontIndex: Int = 0,
    /** Null = default position (both or neither are set). */
    val subtitlePositionX: Float? = null,
    val subtitlePositionY: Float? = null,
    val seekIntervalSeconds: Int = 10,
    val continueWatchingEnabled: Boolean = true,
    /** We asked for "All files access" once already; don't reopen Settings on every launch. */
    val allFilesAccessAsked: Boolean = false,
    /** Null = never set: leave the system brightness alone. */
    val brightness: Float? = null,
)

@Singleton
class PlaybackPreferences @Inject constructor(
    private val store: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private object Keys {
        val fitMode = intPreferencesKey("player_fit_mode_v1")
        val speed = doublePreferencesKey("player_speed_v1")
        val sortBy = intPreferencesKey("folder_sort_by_v1")
        val loopMode = intPreferencesKey("player_loop_mode_v1")
        val scanMode = intPreferencesKey("library_scan_mode_v1")
        val subFontSize = doublePreferencesKey("subtitle_font_size_v1")
        val subColorIndex = intPreferencesKey("subtitle_color_index_v1")
        val subBackground = booleanPreferencesKey("subtitle_background_v1")
        val subBgColorIndex = intPreferencesKey("subtitle_bg_color_index_v1")
        val subFontIndex = intPreferencesKey("subtitle_font_index_v1")
        val subPosX = doublePreferencesKey("subtitle_position_x_v1")
        val subPosY = doublePreferencesKey("subtitle_position_y_v1")
        val seekInterval = intPreferencesKey("player_seek_interval_v1")
        val continueWatching = booleanPreferencesKey("continue_watching_enabled_v1")
        val brightness = doublePreferencesKey("player_brightness")
        val allFilesAsked = booleanPreferencesKey("all_files_access_asked_v1")
    }

    /** Null until the first read finishes; use [current] from suspending code. */
    val settings: StateFlow<PlaybackSettings?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            val x = p[Keys.subPosX]
            val y = p[Keys.subPosY]
            PlaybackSettings(
                fitModeIndex = p[Keys.fitMode] ?: 0,
                speed = (p[Keys.speed] ?: 1.0).toFloat(),
                sortByIndex = p[Keys.sortBy] ?: 0,
                loopModeIndex = p[Keys.loopMode] ?: 0,
                scanMode = LibraryScanMode.entries.getOrElse(p[Keys.scanMode] ?: 0) { LibraryScanMode.Hybrid },
                subtitleFontSize = (p[Keys.subFontSize] ?: 32.0).toFloat(),
                subtitleColorIndex = p[Keys.subColorIndex] ?: 0,
                subtitleBackground = p[Keys.subBackground] ?: true,
                subtitleBgColorIndex = p[Keys.subBgColorIndex] ?: 0,
                subtitleFontIndex = p[Keys.subFontIndex] ?: 0,
                subtitlePositionX = if (x != null && y != null) x.toFloat() else null,
                subtitlePositionY = if (x != null && y != null) y.toFloat() else null,
                seekIntervalSeconds = p[Keys.seekInterval] ?: 10,
                continueWatchingEnabled = p[Keys.continueWatching] ?: true,
                brightness = p[Keys.brightness]?.toFloat(),
                allFilesAccessAsked = p[Keys.allFilesAsked] ?: false,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(): PlaybackSettings = settings.filterNotNull().first()

    fun setFitModeIndex(i: Int) = save { it[Keys.fitMode] = i }
    fun setSpeed(speed: Float) = save { it[Keys.speed] = speed.toDouble() }
    fun setSortByIndex(i: Int) = save { it[Keys.sortBy] = i }
    fun setLoopModeIndex(i: Int) = save { it[Keys.loopMode] = i }
    fun setScanMode(mode: LibraryScanMode) = save { it[Keys.scanMode] = mode.ordinal }
    fun setSubtitleFontSize(size: Float) = save { it[Keys.subFontSize] = size.toDouble() }
    fun setSubtitleColorIndex(i: Int) = save { it[Keys.subColorIndex] = i }
    fun setSubtitleBackground(on: Boolean) = save { it[Keys.subBackground] = on }
    fun setSubtitleBgColorIndex(i: Int) = save { it[Keys.subBgColorIndex] = i }
    fun setSubtitleFontIndex(i: Int) = save { it[Keys.subFontIndex] = i }
    fun setSeekInterval(seconds: Int) = save { it[Keys.seekInterval] = seconds }
    fun setContinueWatchingEnabled(on: Boolean) = save { it[Keys.continueWatching] = on }
    fun setBrightness(value: Float) = save { it[Keys.brightness] = value.toDouble() }
    fun setAllFilesAccessAsked() = save { it[Keys.allFilesAsked] = true }

    /** Pass nulls to reset to the default position. */
    fun setSubtitlePosition(x: Float?, y: Float?) = save {
        if (x == null || y == null) {
            it.remove(Keys.subPosX)
            it.remove(Keys.subPosY)
        } else {
            it[Keys.subPosX] = x.toDouble()
            it[Keys.subPosY] = y.toDouble()
        }
    }

    /** Fire-and-forget; join() the returned job when a later read must see the new value. */
    private fun save(block: (MutablePreferences) -> Unit): Job =
        scope.launch {
            try {
                store.edit { block(it) }
            } catch (_: IOException) {
            }
        }
}
