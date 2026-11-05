package dev.zapstore.app.account

import android.content.Context
import dev.zapstore.app.SecretStore
import dev.zapstore.iolite.Nip55Signer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A Zapstore account: a pubkey held by a NIP-55 signer app. Zapstore never holds its nsec. */
data class Account(val pubkey: String, val signerPackage: String)

/**
 * The signed-in account. Browse, install, and update need none.
 * The pubkey sits in [SecretStore] under the key Flutter used; the package is not secret.
 */
class AccountStore(
    context: Context,
    private val secrets: SecretStore,
    val bridge: Nip55Bridge,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(load())
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** Opens the signer app. Throws [Nip55Exception] when it is cancelled or missing. */
    suspend fun signIn(signerPackage: String? = null): Account {
        val (pubkey, pkg) = bridge.getPublicKey(signerPackage)
        secrets.put(SecretStore.AMBER_PUBKEY, pubkey)
        prefs.edit().putString(KEY_PACKAGE, pkg).apply()
        return Account(pubkey, pkg).also { _account.value = it }
    }

    fun signOut() {
        secrets.remove(SecretStore.AMBER_PUBKEY)
        prefs.edit().remove(KEY_PACKAGE).apply()
        _account.value = null
    }

    /** Signs with the account; null when signed out. */
    fun signer(): Nip55Signer? {
        val current = _account.value ?: return null
        return Nip55Signer(current.pubkey) { json -> bridge.signEvent(json, current.pubkey, current.signerPackage) }
    }

    suspend fun encryptToSelf(plaintext: String): String {
        val current = _account.value ?: throw Nip55Exception("Not signed in")
        return bridge.nip44Encrypt(plaintext, current.pubkey, current.pubkey, current.signerPackage)
    }

    suspend fun decryptFromSelf(payload: String): String {
        val current = _account.value ?: throw Nip55Exception("Not signed in")
        return bridge.nip44Decrypt(payload, current.pubkey, current.pubkey, current.signerPackage)
    }

    private fun load(): Account? {
        val pubkey = secrets.getOrNull(SecretStore.AMBER_PUBKEY) ?: return null
        return Account(pubkey, prefs.getString(KEY_PACKAGE, null) ?: Nip55Bridge.AMBER)
    }

    private companion object {
        const val PREFS = "account"
        const val KEY_PACKAGE = "signer_package"
    }
}
