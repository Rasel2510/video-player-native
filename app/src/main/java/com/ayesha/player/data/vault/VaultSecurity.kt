package com.ayesha.player.data.vault

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** vault_settings_service.dart presets. The stored value is raw seconds; -1 = never. */
enum class VaultAutoLock(val seconds: Int, val label: String) {
    Immediate(0, "Immediately"),
    Seconds30(30, "30 seconds"),
    Minute1(60, "1 minute"),
    Minutes5(300, "5 minutes"),
    Minutes15(900, "15 minutes"),
    Never(-1, "Never"),
}

/** Pure helpers, unit-tested. */
object VaultRules {
    /** Same as the Dart build: sha256("salt:pin") as lowercase hex. */
    fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun newSalt(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** "Never", "Immediately", "30 seconds", "1 minute", "1.5 minutes". */
    fun formatAutoLock(seconds: Int): String = when {
        seconds < 0 -> "Never"
        seconds == 0 -> "Immediately"
        seconds < 60 -> "$seconds second${if (seconds == 1) "" else "s"}"
        seconds % 60 == 0 -> (seconds / 60).let { "$it minute${if (it == 1) "" else "s"}" }
        else -> String.format(java.util.Locale.ROOT, "%.1f minutes", seconds / 60.0)
    }
}

/**
 * The vault's own PIN (vault_pin_service.dart) and auto-lock timer (vault_settings_service.dart).
 * The PIN is independent of the device unlock and only stored as a salted hash. Keys and the
 * hash format match the Flutter build.
 */
@Singleton
class VaultSecurity @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    private object Keys {
        val hash = stringPreferencesKey("vault_pin_hash_v1")
        val salt = stringPreferencesKey("vault_pin_salt_v1")
        val autoLock = intPreferencesKey("vault_auto_lock_seconds_v1")
    }

    suspend fun hasPin(): Boolean = store.data.first()[Keys.hash] != null

    suspend fun setPin(pin: String) {
        val salt = VaultRules.newSalt()
        // Salt first: if the hash write fails, hasPin() is still (correctly) false.
        store.edit { it[Keys.salt] = salt }
        store.edit { it[Keys.hash] = VaultRules.hash(pin, salt) }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val p = store.data.first()
        val salt = p[Keys.salt] ?: return false
        val hash = p[Keys.hash] ?: return false
        return MessageDigest.isEqual(VaultRules.hash(pin, salt).toByteArray(), hash.toByteArray())
    }

    suspend fun autoLockSeconds(): Int = store.data.first()[Keys.autoLock] ?: DEFAULT_AUTO_LOCK

    suspend fun setAutoLockSeconds(seconds: Int) {
        store.edit { it[Keys.autoLock] = seconds }
    }

    private companion object {
        const val DEFAULT_AUTO_LOCK = 60
    }
}
