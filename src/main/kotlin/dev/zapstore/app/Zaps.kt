package dev.zapstore.app

import dev.zapstore.iolite.Event
import dev.zapstore.iolite.NwcConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

/** The NWC connection, kept in [SecretStore] and never in device state or logs. */
class Wallet(private val secrets: SecretStore) {
    private val state = MutableStateFlow(secrets.getOrNull(SecretStore.NWC)?.let(NwcConnection::parse))
    val connection: StateFlow<NwcConnection?> = state.asStateFlow()

    /** False when [input] is not a NWC connection string. */
    fun connect(input: String): Boolean {
        val connection = NwcConnection.parse(input) ?: return false
        secrets.put(SecretStore.NWC, input.trim())
        state.value = connection
        return true
    }

    fun disconnect() {
        secrets.remove(SecretStore.NWC)
        state.value = null
    }
}

/** Why a zap stopped; each maps to one sentence for the person. */
enum class ZapFailure { NoAddress, NoZaps, Amount, Invoice, Unreachable }

class ZapException(val failure: ZapFailure) : Exception(failure.name)

/** A LUD-16 address's pay endpoint, NIP-57 fields included. */
data class LnurlPay(val callback: String, val minMsat: Long, val maxMsat: Long, val nostrPubkey: String?)

/** LUD-06/16 over the app's HTTP client, so Tor applies when it is on. */
class Lnurl(private val calls: Call.Factory) {
    suspend fun pay(address: String): LnurlPay {
        val (name, domain) = address.trim().lowercase().split('@').takeIf { it.size == 2 && it.all(String::isNotBlank) }
            ?: throw ZapException(ZapFailure.NoAddress)
        val json = get("https://$domain/.well-known/lnurlp/$name")
        if (json.optString("tag") != "payRequest") throw ZapException(ZapFailure.NoAddress)
        if (!json.optBoolean("allowsNostr")) throw ZapException(ZapFailure.NoZaps)
        return LnurlPay(
            callback = json.getString("callback"),
            minMsat = json.optLong("minSendable", 1_000),
            maxMsat = json.optLong("maxSendable", Long.MAX_VALUE),
            nostrPubkey = json.optString("nostrPubkey").takeIf { it.length == 64 },
        )
    }

    /** A BOLT11 invoice for [amountMsat] carrying the signed [zapRequest]. */
    suspend fun invoice(pay: LnurlPay, amountMsat: Long, zapRequest: Event): String {
        // LUD-06: clear HTTP only to an onion, or anyone on the path (a Tor exit) could swap the invoice.
        val url = pay.callback.toHttpUrlOrNull()
            ?.takeIf { it.isHttps || it.host.endsWith(".onion") }
            ?.newBuilder()
            ?.addQueryParameter("amount", amountMsat.toString())
            ?.addQueryParameter("nostr", zapRequest.toJson())
            ?.build() ?: throw ZapException(ZapFailure.Invoice)
        return get(url.toString()).optString("pr").takeIf { it.isNotBlank() } ?: throw ZapException(ZapFailure.Invoice)
    }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val response = runCatching { calls.newCall(Request.Builder().url(url).build()).execute() }
            .getOrElse { throw ZapException(ZapFailure.Unreachable) }
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) throw ZapException(ZapFailure.Unreachable)
            val json = runCatching { JSONObject(body) }.getOrElse { throw ZapException(ZapFailure.Unreachable) }
            if (json.optString("status") == "ERROR") throw ZapException(ZapFailure.Invoice)
            json
        }
    }
}
