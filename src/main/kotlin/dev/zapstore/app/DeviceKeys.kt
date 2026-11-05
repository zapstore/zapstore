package dev.zapstore.app

import android.content.Context
import dev.zapstore.iolite.Crypto
import dev.zapstore.iolite.Hex
import dev.zapstore.iolite.LocalSigner
import dev.zapstore.iolite.decodeNsec

object DeviceKeys {
    /**
     * The device key: carried from Flutter, moved from the old plaintext prefs, or new. Throws
     * [UnreadableSecretException] rather than replace a stored key the Keystore failed to open.
     */
    fun loadOrCreate(context: Context, secrets: SecretStore): LocalSigner {
        secrets.get(SecretStore.DEVICE_KEY)?.let { return LocalSigner(Hex.decode(it)) }
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val secret = legacy.getString(LEGACY_KEY, null)?.let(Hex::decode) ?: Crypto.randomBytes(32)
        secrets.put(SecretStore.DEVICE_KEY, Hex.encode(secret))
        context.deleteSharedPreferences(LEGACY_PREFS)
        return LocalSigner(secret)
    }

    /** A pasted key as hex: an `nsec`, or 64 hex characters. Null for anything else. */
    fun parse(input: String): String? {
        val value = input.trim()
        value.decodeNsec()?.let { return it }
        return value.lowercase().takeIf { HEX_KEY.matches(it) }
    }

    /**
     * Replaces the device key (paste restore or signer backup) and marks the new key's events to be
     * fetched on the next start. The caller restarts the process: everything open used the old key.
     */
    fun replace(context: Context, secrets: SecretStore, hex: String) {
        val normalized = parse(hex) ?: throw IllegalArgumentException("not a device key")
        secrets.put(SecretStore.DEVICE_KEY, normalized)
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(RESTORE_PENDING, true).commit()
    }

    /** True once after [replace]: the restored key's settings and lists are still on the relay. */
    fun takeRestorePending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(RESTORE_PENDING, false)) return false
        prefs.edit().remove(RESTORE_PENDING).apply()
        return true
    }

    private const val LEGACY_PREFS = "device_keys"
    private const val LEGACY_KEY = "nsec"
    private const val STATE_PREFS = "device_key_state"
    private const val RESTORE_PENDING = "restore_pending"
    private val HEX_KEY = Regex("^[0-9a-f]{64}$")
}
