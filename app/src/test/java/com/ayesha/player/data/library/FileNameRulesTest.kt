package com.ayesha.player.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNameRulesTest {

    @Test fun `split keeps the extension fixed`() {
        assertEquals("Movie (1)" to ".mkv", FileNameRules.splitName("Movie (1).mkv"))
        assertEquals("README" to "", FileNameRules.splitName("README"))
    }

    @Test fun `illegal characters become underscores`() {
        assertEquals("a_b_c_d", FileNameRules.sanitizeBaseName("a/b:c?d"))
    }

    @Test fun `trailing dots and spaces are stripped`() {
        assertEquals("clip", FileNameRules.sanitizeBaseName("clip. . "))
    }

    @Test fun `hidden and reserved names are prefixed`() {
        assertEquals("_.secret", FileNameRules.sanitizeBaseName(".secret"))
        assertEquals("_con", FileNameRules.sanitizeBaseName("con"))
        assertEquals("_LPT1", FileNameRules.sanitizeBaseName("LPT1"))
    }

    @Test fun `nothing usable left gives null`() {
        assertNull(FileNameRules.sanitizeBaseName("..."))
        assertNull(FileNameRules.sanitizeBaseName("   "))
    }
}
