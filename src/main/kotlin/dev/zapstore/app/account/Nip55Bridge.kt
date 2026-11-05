package dev.zapstore.app.account

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import dev.zapstore.iolite.Hex
import dev.zapstore.iolite.decodeNpub
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.minutes

class Nip55Exception(message: String) : Exception(message)

/**
 * Talks to a NIP-55 signer app such as Amber.
 * A remembered permission is answered by the signer's content provider with no screen.
 * Otherwise the signer opens through the activity attached with [attach].
 * One request runs at a time; the signer shows one screen at a time.
 */
class Nip55Bridge(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var waiting: CompletableDeferred<Intent?>? = null

    /** Signer apps that handle `nostrsigner:`. */
    fun installedSigners(): List<String> =
        appContext.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri()), 0)
            .map { it.activityInfo.packageName }
            .distinct()

    /** Call from [ComponentActivity.onCreate], before the activity starts. */
    fun attach(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            waiting?.complete(result.data.takeIf { result.resultCode == Activity.RESULT_OK })
        }
    }

    fun detach(activity: ComponentActivity) {
        if (activity.isChangingConfigurations) return
        launcher = null
        waiting?.complete(null)
    }

    /** Hex pubkey and the signer package that answered. */
    suspend fun getPublicKey(signerPackage: String?): Pair<String, String> {
        val intent = signerIntent("", TYPE_PUBLIC_KEY, signerPackage, null)
            .putExtra("permissions", PERMISSIONS)
        val data = launch(intent) ?: throw Nip55Exception("Sign-in was cancelled")
        val result = data.getStringExtra("result").orEmpty()
        val pubkey = normalizePubkey(result) ?: throw Nip55Exception("Signer returned no public key")
        val pkg = data.getStringExtra("package") ?: signerPackage ?: throw Nip55Exception("Signer returned no package")
        return pubkey to pkg
    }

    /** The signed event JSON. */
    suspend fun signEvent(unsignedJson: String, pubkey: String, signerPackage: String): String =
        request(TYPE_SIGN_EVENT, unsignedJson, pubkey, signerPackage, extraPubkey = null, column = "event")

    suspend fun nip44Encrypt(plaintext: String, to: String, pubkey: String, signerPackage: String): String =
        request(TYPE_ENCRYPT, plaintext, pubkey, signerPackage, extraPubkey = to, column = "result")

    suspend fun nip44Decrypt(payload: String, from: String, pubkey: String, signerPackage: String): String =
        request(TYPE_DECRYPT, payload, pubkey, signerPackage, extraPubkey = from, column = "result")

    private suspend fun request(
        type: String,
        content: String,
        pubkey: String,
        signerPackage: String,
        extraPubkey: String?,
        column: String,
    ): String {
        query(type, content, pubkey, signerPackage, extraPubkey, column)?.let { return it }
        val intent = signerIntent(content, type, signerPackage, pubkey)
        if (extraPubkey != null) intent.putExtra("pubkey", extraPubkey)
        val data = launch(intent) ?: throw Nip55Exception("Signer request was cancelled")
        return data.getStringExtra(column)?.takeIf { it.isNotEmpty() }
            ?: throw Nip55Exception("Signer returned no $column")
    }

    /** Null when the signer wants its screen: no remembered permission, or no provider. */
    private suspend fun query(
        type: String,
        content: String,
        pubkey: String,
        signerPackage: String,
        extraPubkey: String?,
        column: String,
    ): String? = withContext(Dispatchers.IO) {
        val authority = "$signerPackage.${type.uppercase()}"
        // Any app can claim an authority the signer does not declare; only the signer's own provider may answer.
        val owner = runCatching { appContext.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
        if (owner != signerPackage) return@withContext null
        val uri = "content://$authority".toUri()
        val args = arrayOf(content, extraPubkey.orEmpty(), pubkey)
        val cursor = runCatching { appContext.contentResolver.query(uri, args, null, null, null) }.getOrNull()
            ?: return@withContext null
        cursor.use {
            if (it.getColumnIndex("rejected") >= 0) throw Nip55Exception("Signer rejected the request")
            if (!it.moveToFirst()) return@withContext null
            val index = it.getColumnIndex(column)
            if (index < 0) null else it.getString(index)?.takeIf { value -> value.isNotEmpty() }
        }
    }

    private suspend fun launch(intent: Intent): Intent? = lock.withLock {
        val current = launcher ?: throw Nip55Exception("Open Zapstore to use the signer")
        val deferred = CompletableDeferred<Intent?>()
        waiting = deferred
        withContext(Dispatchers.Main) {
            try {
                current.launch(intent)
            } catch (_: ActivityNotFoundException) {
                throw Nip55Exception("No signer app is installed")
            }
        }
        try {
            // A signer that never shows its screen, or one the person leaves, must not hold the lock forever.
            try {
                withTimeout(SIGNER_TIMEOUT) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                throw Nip55Exception("The signer did not answer")
            }
        } finally {
            waiting = null
        }
    }

    private fun signerIntent(content: String, type: String, signerPackage: String?, pubkey: String?): Intent =
        Intent(Intent.ACTION_VIEW, "nostrsigner:${Uri.encode(content)}".toUri()).apply {
            signerPackage?.let(::setPackage)
            putExtra("type", type)
            pubkey?.let { putExtra("current_user", it) }
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    companion object {
        const val AMBER = "com.greenart7c3.nostrsigner"
        private val SIGNER_TIMEOUT = 2.minutes
        private const val TYPE_PUBLIC_KEY = "get_public_key"
        private const val TYPE_SIGN_EVENT = "sign_event"
        private const val TYPE_ENCRYPT = "nip44_encrypt"
        private const val TYPE_DECRYPT = "nip44_decrypt"

        /** Asked once at sign-in so later requests can be answered with no screen. */
        private val PERMISSIONS: String = JSONArray().apply {
            for (kind in listOf(1111, 9734, 1984, 5312, 30078)) {
                put(JSONObject().put("type", TYPE_SIGN_EVENT).put("kind", kind))
            }
            put(JSONObject().put("type", TYPE_ENCRYPT))
            put(JSONObject().put("type", TYPE_DECRYPT))
        }.toString()

        /** Signers return hex or an npub. */
        fun normalizePubkey(value: String): String? {
            val trimmed = value.trim().lowercase()
            return if (Hex.isHex(trimmed, 64)) trimmed else trimmed.decodeNpub()
        }
    }
}
