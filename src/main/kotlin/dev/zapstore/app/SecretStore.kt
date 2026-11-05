package dev.zapstore.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Keystore failed to open a stored secret. It may work on a later try, so the secret must not be replaced. */
class UnreadableSecretException(name: String, cause: Throwable?) : Exception("secret $name is unreadable", cause)

/** Small secrets (device key, Amber pubkey, NWC string), AES-GCM under an Android Keystore key. */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Null when [name] was never stored, or when it can never be opened again: its Keystore key is gone
     * or the value fails authentication. Throws [UnreadableSecretException] when the Keystore keeps failing.
     */
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        var failure: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                val key = existingKey() ?: return lost(name, null)
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                if (bytes.size <= IV_BYTES) return lost(name, null)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
                return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
            } catch (e: AEADBadTagException) {
                return lost(name, e)
            } catch (e: IllegalArgumentException) {
                return lost(name, e)
            } catch (e: GeneralSecurityException) {
                failure = e
            } catch (e: ProviderException) {
                failure = e
            }
            if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_MS * (attempt + 1))
        }
        throw UnreadableSecretException(name, failure)
    }

    /** [get] for secrets the user can enter again (Amber pubkey, NWC): a Keystore failure reads as absent. */
    fun getOrNull(name: String): String? = try {
        get(name)
    } catch (e: UnreadableSecretException) {
        AppLog.w(TAG, e.message ?: name, e)
        null
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }

    fun remove(name: String) {
        prefs.edit().remove(name).commit()
    }

    private fun lost(name: String, cause: Throwable?): String? {
        AppLog.e(TAG, "secret $name can no longer be decrypted", cause)
        return null
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(ALIAS, null) as? SecretKey
    }

    private fun newKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEVICE_KEY = "device_key"
        const val AMBER_PUBKEY = "amber_pubkey"
        const val NWC = "nwc"

        private const val TAG = "SecretStore"
        private const val PREFS = "secrets"
        private const val ALIAS = "zapstore_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val ATTEMPTS = 3
        private const val RETRY_MS = 100L
    }
}
