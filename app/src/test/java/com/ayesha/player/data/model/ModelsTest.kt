package com.ayesha.player.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ModelsTest {

    // Same configuration as AppModule.json().
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test fun `video extensions`() {
        assertTrue(VideoFile.isVideoFile("Movie.MKV"))
        assertTrue(VideoFile.isVideoFile("clip.m2ts"))
        assertFalse(VideoFile.isVideoFile("song.mp3"))
        assertFalse(VideoFile.isVideoFile("mp4"))
    }

    @Test fun `derived labels`() {
        val v = VideoFile("/x/Trip.Final.MP4", "Trip.Final.MP4", 1536L * 1024, 0)
        assertEquals(".mp4", v.extension)
        assertEquals("MP4", v.extensionLabel)
        assertEquals("1.5 MB", v.sizeLabel)
    }

    @Test fun `json without duration matches Dart toJson`() {
        val v = VideoFile("/a/b.mp4", "b.mp4", 42, 1_700_000_000_000)
        assertEquals(
            """{"path":"/a/b.mp4","name":"b.mp4","size":42,"modified":1700000000000}""",
            json.encodeToString(VideoFile.serializer(), v),
        )
    }

    @Test fun `json round trip with duration`() {
        val v = VideoFile("/a/b.mp4", "b.mp4", 42, 1_700_000_000_000).withDuration(90.seconds)
        val text = json.encodeToString(VideoFile.serializer(), v)
        assertTrue(text.contains("\"duration\":90000"))
        assertEquals(v, json.decodeFromString(VideoFile.serializer(), text))
        assertEquals(90.seconds, v.duration)
    }

    @Test fun `reads json written by the Flutter build`() {
        val dart = """{"path":"/m/x.mkv","name":"x.mkv","size":10,"modified":5,"duration":1234,"extra":true}"""
        val v = json.decodeFromString(VideoFile.serializer(), dart)
        assertEquals(1234L, v.durationMs)
        assertEquals(5L, v.modifiedMs)
    }

    @Test fun `folder aggregates`() {
        val f = VideoFolder(
            "/storage/emulated/0/Movies/",
            listOf(VideoFile("/1", "1.mp4", 1024L * 1024, 0), VideoFile("/2", "2.mp4", 1024L * 1024, 0)),
        )
        assertEquals("Movies", f.name)
        assertEquals(2, f.videoCount)
        assertEquals("2.0 MB", f.totalSizeLabel)
        assertEquals("/", VideoFolder("/", emptyList()).name)
    }

    @Test fun `video size display aspect accounts for rotation`() {
        assertEquals(16f / 9f, VideoSize(1920, 1080).displayAspect, 0.001f)
        assertEquals(9f / 16f, VideoSize(1920, 1080, 90).displayAspect, 0.001f)
        assertTrue(VideoSize.Zero.isEmpty)
    }
}
