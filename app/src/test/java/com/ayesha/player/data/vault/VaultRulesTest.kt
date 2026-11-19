package com.ayesha.player.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRulesTest {

    @Test fun `hash is sha256 of salt colon pin as lowercase hex, like the Dart build`() {
        // Reference value: printf "%s" "abc:123456" | sha256sum
        assertEquals("71f79ddc5dba7d98fd09ade46f1d21c8c1ea9965b2acc9b4ccaf41fba8dd808e", VaultRules.hash("123456", "abc"))
        val h = VaultRules.hash("123456", "abc")
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(h, VaultRules.hash("123456", "abc"))
        assertNotEquals(h, VaultRules.hash("123457", "abc"))
        assertNotEquals(h, VaultRules.hash("123456", "abd"))
    }

    @Test fun `salt is 16 random bytes as 32 hex chars`() {
        val s = VaultRules.newSalt()
        assertEquals(32, s.length)
        assertNotEquals(s, VaultRules.newSalt())
    }

    @Test fun `auto lock labels`() {
        assertEquals("Never", VaultRules.formatAutoLock(-1))
        assertEquals("Immediately", VaultRules.formatAutoLock(0))
        assertEquals("1 second", VaultRules.formatAutoLock(1))
        assertEquals("30 seconds", VaultRules.formatAutoLock(30))
        assertEquals("1 minute", VaultRules.formatAutoLock(60))
        assertEquals("15 minutes", VaultRules.formatAutoLock(900))
        assertEquals("1.5 minutes", VaultRules.formatAutoLock(90))
    }
}
