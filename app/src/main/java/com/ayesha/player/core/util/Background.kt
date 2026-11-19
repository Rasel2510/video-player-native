package com.ayesha.player.core.util

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-priority threads for heavy background media work. On a low-end phone, decoding thumbnail
 * frames, probing durations and walking storage at normal priority steal CPU from the UI and
 * render threads, which shows up as scroll stutter. At THREAD_PRIORITY_BACKGROUND the screen
 * always gets the CPU first and this work fills the gaps.
 */
object Background {
    /** Thumbnail frame extraction: 3 at a time (was 6, too many for a 4-core budget chip). */
    val thumbnails: CoroutineDispatcher = pool("ayesha-thumbs", 3)

    /** Duration probes, the storage walk and change checks. */
    val media: CoroutineDispatcher = pool("ayesha-media", 2)

    private fun pool(name: String, threads: Int): CoroutineDispatcher {
        val n = AtomicInteger()
        return Executors.newFixedThreadPool(threads) { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "$name-${n.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
