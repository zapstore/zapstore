package dev.zapstore.app

import android.content.Context
import android.content.Intent
import dev.zapstore.app.account.AccountStore
import dev.zapstore.iolite.DeviceEvents
import dev.zapstore.iolite.Filter
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.Kinds
import dev.zapstore.iolite.toNsec
import org.json.JSONObject

/**
 * The device key's two ways off the phone: an account-signed `zapstore-device-key-backup`
 * (NIP-44 to the account, through the signer app), and the `nsec` the user copies by hand.
 */
class DeviceKeyBackup(
    private val context: Context,
    private val secrets: SecretStore,
    private val iolite: Iolite,
    private val accounts: AccountStore,
) {
    private fun deviceKeyHex(): String = secrets.get(SecretStore.DEVICE_KEY) ?: error("device key is missing")

    fun nsec(): String = deviceKeyHex().toNsec()

    /** Encrypts the key to the account and publishes it. Opens the signer app twice: encrypt, then sign. */
    suspend fun backUp() {
        val signer = accounts.signer() ?: error("Sign in first")
        val content = accounts.encryptToSelf(JSONObject().put("deviceKey", deviceKeyHex()).toString())
        iolite.publish(Kinds.Preferences, listOf(listOf("d", DeviceEvents.KEY_BACKUP)), content, signer)
    }

    /** The newest backup on the relays for the signed-in account, decrypted. Null when there is none. */
    suspend fun fetchBackup(): String? {
        val account = accounts.account.value ?: error("Sign in first")
        val event = iolite.fetch(
            listOf(
                Filter(
                    authors = listOf(account.pubkey),
                    kinds = listOf(Kinds.Preferences),
                    tags = mapOf("d" to listOf(DeviceEvents.KEY_BACKUP)),
                    limit = 1,
                ),
            ),
            relays = AppConfig.writeRelays + AppConfig.catalogRelay,
        ).firstOrNull() ?: return null
        val plaintext = accounts.decryptFromSelf(event.content)
        return DeviceKeys.parse(JSONObject(plaintext).optString("deviceKey"))
    }

    /** Swaps the key and restarts, so nothing keeps signing with the old one. */
    fun restore(hex: String) {
        DeviceKeys.replace(context, secrets, hex)
        restart(context)
    }

    fun isCurrent(hex: String): Boolean = DeviceKeys.parse(hex) == deviceKeyHex()
}

/** Relaunches the app in a fresh process. */
fun restart(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}
