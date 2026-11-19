package com.ayesha.player.data.media

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * software_probe_service.dart on libmpv: duration and a thumbnail frame for files that
 * MediaMetadataRetriever can't open (typically codecs the device can't decode). Each probe
 * uses a short-lived, silent mpv instance (no audio, no window); one at a time, since each
 * spins up a software decoder.
 */
@Singleton
class MpvSoftwareProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : SoftwareProbe {

    private val gate = Mutex()

    override suspend fun probeDuration(path: String): Duration? = gate.withLock {
        withContext(Dispatchers.IO) { withTimeoutOrNull(8_000) { probe(path, grabFrame = false).first } }
    }

    override suspend fun grabThumbnail(path: String): ByteArray? = gate.withLock {
        withContext(Dispatchers.IO) { withTimeoutOrNull(12_000) { probe(path, grabFrame = true).second } }
    }

    private suspend fun probe(path: String, grabFrame: Boolean): Pair<Duration?, ByteArray?> {
        if (path.startsWith("content://")) return null to null
        val mpv = try {
            MPVLib.create(context)
        } catch (_: Throwable) {
            null
        } ?: return null to null
        val duration = CompletableDeferred<Double?>()
        val frameReady = CompletableDeferred<Unit>()
        val observer = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) = Unit
            override fun eventProperty(property: String, value: Long) {
                if (property == "dwidth" && value > 0) frameReady.complete(Unit)
            }
            override fun eventProperty(property: String, value: Double) {
                if (property == "duration" && value > 0) duration.complete(value)
            }
            override fun eventProperty(property: String, value: Boolean) = Unit
            override fun eventProperty(property: String, value: String) = Unit
            override fun event(eventId: Int) {
                if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                    duration.complete(null)
                    frameReady.complete(Unit)
                }
            }
        }
        return try {
            // No window: frames are still decoded for the screenshot, just never displayed.
            mpv.setOptionString("vo", "null")
            mpv.setOptionString("ao", "null")
            mpv.setOptionString("hwdec", "no")
            mpv.setOptionString("pause", if (grabFrame) "no" else "yes")
            mpv.setOptionString("mute", "yes")
            mpv.setOptionString("keep-open", "yes")
            if (!grabFrame) mpv.setOptionString("vid", "no") // demux only: works even for undecodable video
            if (grabFrame) mpv.setOptionString("start", "1")
            mpv.init()
            mpv.addObserver(observer)
            mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("dwidth", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            mpv.command(arrayOf("loadfile", path))
            if (!grabFrame) {
                duration.await()?.let { (it * 1000).toLong().milliseconds } to null
            } else {
                frameReady.await()
                val out = File(context.cacheDir, "probe_${SystemClock.elapsedRealtime()}.jpg")
                try {
                    mpv.command(arrayOf("screenshot-to-file", out.path, "video"))
                    null to out.takeIf { it.exists() && it.length() > 0 }?.readBytes()
                } finally {
                    out.delete()
                }
            }
        } catch (_: Exception) {
            null to null
        } finally {
            runCatching { mpv.removeObserver(observer) }
            runCatching { mpv.destroy() }
        }
    }
}
