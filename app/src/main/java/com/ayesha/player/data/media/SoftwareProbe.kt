package com.ayesha.player.data.media

import kotlin.time.Duration

/**
 * Last-resort duration and thumbnail extraction in software (software_probe_service.dart),
 * for files MediaMetadataRetriever can't open at all, typically codecs the device can't
 * decode (some 10-bit HEVC). Implemented by [MpvSoftwareProbe].
 */
interface SoftwareProbe {
    suspend fun probeDuration(path: String): Duration?

    /** One decoded frame as JPEG bytes, or null. */
    suspend fun grabThumbnail(path: String): ByteArray?
}
