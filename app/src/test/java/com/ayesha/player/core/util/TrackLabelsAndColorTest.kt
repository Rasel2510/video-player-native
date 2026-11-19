package com.ayesha.player.core.util

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLabelsAndColorTest {

    @Test fun `placeholder ids`() {
        assertTrue(TrackLabels.isPlaceholderId("auto"))
        assertTrue(TrackLabels.isPlaceholderId("no"))
        assertFalse(TrackLabels.isPlaceholderId("0:1"))
    }

    @Test fun `language names`() {
        assertEquals("Bengali", TrackLabels.languageName("ben"))
        assertEquals("English", TrackLabels.languageName(" EN "))
        assertEquals("XYZ", TrackLabels.languageName("xyz"))
        assertNull(TrackLabels.languageName("und"))
        assertNull(TrackLabels.languageName(""))
        assertNull(TrackLabels.languageName(null))
    }

    @Test fun `track label precedence`() {
        assertEquals("Director's commentary", TrackLabels.trackLabel("Director's commentary", "eng", 0, 3))
        assertEquals("Default", TrackLabels.trackLabel(null, "eng", 0, 1))
        assertEquals("Japanese", TrackLabels.trackLabel("  ", "jpn", 1, 2))
        assertEquals("Track 3", TrackLabels.trackLabel(null, "und", 2, 3))
    }

    @Test fun `srgb lerp matches Flutter Color lerp`() {
        val a = Color(0xFF000000)
        val b = Color(0xFFFFFFFF)
        assertEquals(Color(0xFF808080), srgbLerp(a, b, 0.5f))
        assertEquals(a, srgbLerp(a, b, 0f))
        assertEquals(b, srgbLerp(a, b, 1f))
    }

    @Test fun `volume color ramps only above 100 percent`() {
        val accent = Color(0xFF3B82F6)
        assertEquals(accent, VolumeColor.barColor(80f, accent))
        assertEquals(accent, VolumeColor.barColor(100f, accent))
        assertEquals(VolumeColor.boostGradEnd, VolumeColor.barColor(200f, accent))
        assertEquals(VolumeColor.boost, VolumeColor.forVolume(250f, accent))
        assertEquals(0.5f, VolumeColor.boostT(150f), 0.0001f)
    }
}
