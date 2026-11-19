package com.ayesha.player.data.device

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Device media volume, 0..1 (volume_service.dart / flutter_volume_controller).
 * Changes are silent (no system volume panel). Boost above 100 % is applied by the player
 * with LoudnessEnhancer, not here.
 */
@Singleton
class VolumeController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val stream = AudioManager.STREAM_MUSIC
    private val max: Int get() = audio.getStreamMaxVolume(stream).coerceAtLeast(1)

    fun get(): Float = try {
        (audio.getStreamVolume(stream).toFloat() / max).coerceIn(0f, 1f)
    } catch (_: Exception) {
        1f
    }

    fun set(volume: Float) {
        try {
            // Flags = 0: no FLAG_SHOW_UI, so the system slider stays hidden.
            audio.setStreamVolume(stream, (volume.coerceIn(0f, 1f) * max).roundToInt(), 0)
        } catch (_: SecurityException) {
            // Thrown while Do Not Disturb blocks volume changes.
        }
    }

    /** Volume changes made anywhere (hardware keys, other apps). */
    fun changes(): Flow<Float> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(get())
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        trySend(get())
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()
}
