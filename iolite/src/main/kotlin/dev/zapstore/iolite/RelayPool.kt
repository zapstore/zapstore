package dev.zapstore.iolite

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What one relay says about one subscription. */
internal interface SubscriptionListener {
    fun onEvent(relay: RelayUrl, event: Event)
    fun onEose(relay: RelayUrl)

    /** The relay ended the subscription with `CLOSED`. Not an answer: nothing says the data is complete. */
    fun onClosed(relay: RelayUrl, reason: String)

    /** The connection failed or dropped; the pool reconnects and resends the `REQ`. */
    fun onConnectionError(relay: RelayUrl, reason: String)
}

internal class RelayPool(
    private val factory: WebSocketFactory,
    private val scope: CoroutineScope,
    private val signer: Signer?,
    private val nowSeconds: () -> Long,
    val liveness: RelayLiveness,
) {
    private val connections = ConcurrentHashMap<RelayUrl, RelayConnection>()
    private val trafficEnabled = AtomicBoolean(true)

    /** Whether a relay's `auth-required:` can be met. */
    val canAuthenticate: Boolean get() = signer != null

    fun setTrafficEnabled(enabled: Boolean) {
        trafficEnabled.set(enabled)
        if (!enabled) {
            connections.values.forEach { it.close("traffic disabled") }
        } else {
            connections.values.forEach { it.ensureOpen() }
        }
    }

    fun refreshConnections() {
        connections.values.forEach { it.reconnect("refresh") }
    }

    /** Opens or replaces subscription [id] on [relay]; the same id sends a new `REQ` that replaces the old one. */
    fun subscribe(relay: RelayUrl, id: String, filters: List<Filter>, listener: SubscriptionListener) {
        connection(relay).add(Subscription(id, filters, listener))
    }

    fun unsubscribe(relay: RelayUrl, id: String) {
        connections[relay]?.remove(id)
    }

    /** The same subscription on every relay in [relays]. Returns the closer. */
    fun subscribe(id: String, filters: List<Filter>, relays: Set<RelayUrl>, listener: SubscriptionListener): () -> Unit {
        relays.forEach { subscribe(it, id, filters, listener) }
        return { relays.forEach { unsubscribe(it, id) } }
    }

    fun publish(event: Event, relays: Set<RelayUrl>, onOk: (RelayUrl, Boolean, String) -> Unit) {
        relays.forEach { url -> connection(url).publish(event, onOk) }
    }

    fun close() {
        trafficEnabled.set(false)
        connections.values.forEach { it.close("closed") }
        connections.clear()
    }

    private fun connection(url: RelayUrl): RelayConnection =
        connections.getOrPut(url) {
            RelayConnection(
                url = url,
                factory = factory,
                scope = scope,
                signer = signer,
                nowSeconds = nowSeconds,
                trafficEnabled = trafficEnabled,
                liveness = liveness,
            )
        }.also { it.ensureOpen() }
}

private class Subscription(
    val id: String,
    val filters: List<Filter>,
    val listener: SubscriptionListener,
)

private class RelayConnection(
    private val url: RelayUrl,
    private val factory: WebSocketFactory,
    private val scope: CoroutineScope,
    private val signer: Signer?,
    private val nowSeconds: () -> Long,
    private val trafficEnabled: AtomicBoolean,
    private val liveness: RelayLiveness,
) {
    private val lock = Any()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val okWaiters = ConcurrentHashMap<String, CopyOnWriteArrayList<(RelayUrl, Boolean, String) -> Unit>>()
    private val pendingEvents = ConcurrentHashMap<String, Event>()
    private var session: WebSocketSession? = null
    private var reconnectJob: Job? = null
    private var authEventId: String? = null
    private var connected = false
    /** Bumped on close so a socket we replaced cannot fail the connection that took its place. */
    private var epoch = 0

    fun add(subscription: Subscription) {
        subscriptions[subscription.id] = subscription
        ensureOpen()
        synchronized(lock) {
            if (connected) sendReq(subscription)
        }
    }

    fun remove(id: String) {
        subscriptions.remove(id) ?: return
        synchronized(lock) {
            if (connected) session?.send(JSONArray().put("CLOSE").put(id).toString())
        }
        if (subscriptions.isEmpty() && pendingEvents.isEmpty()) {
            close("idle")
        }
    }

    fun publish(event: Event, onOk: (RelayUrl, Boolean, String) -> Unit) {
        pendingEvents[event.id] = event
        okWaiters.getOrPut(event.id) { CopyOnWriteArrayList() }.add(onOk)
        ensureOpen()
        synchronized(lock) {
            if (connected) sendEvent(event)
        }
    }

    fun ensureOpen() {
        if (!trafficEnabled.get()) return
        synchronized(lock) {
            if (session != null || reconnectJob?.isActive == true) return
            openLocked()
        }
    }

    fun reconnect(reason: String) {
        close(reason)
        ensureOpen()
    }

    fun close(reason: String) {
        synchronized(lock) {
            epoch++
            reconnectJob?.cancel()
            reconnectJob = null
            val closing = session
            session = null
            connected = false
            authEventId = null
            runCatching { closing?.close() }
        }
    }

    private fun openLocked() {
        val openedAt = epoch
        val listener = object : WebSocketListener {
            override fun onOpen() {
                synchronized(lock) {
                    if (openedAt != epoch) return
                    connected = true
                    subscriptions.values.forEach(::sendReq)
                    pendingEvents.values.forEach(::sendEvent)
                }
                liveness.recordSuccess(url)
            }

            override fun onMessage(text: String) {
                if (synchronized(lock) { openedAt != epoch }) return
                handle(text)
            }

            override fun onClosing(code: Int, reason: String) {
                val current = synchronized(lock) {
                    if (openedAt != epoch) return
                    connected = false
                    session = null
                    true
                }
                if (!current) return
                if (code != NORMAL_CLOSURE && code != GOING_AWAY) liveness.recordFailure(url)
                lost(reason.ifBlank { "closed ($code)" })
            }

            override fun onFailure(error: Throwable) {
                val current = synchronized(lock) {
                    if (openedAt != epoch) return
                    connected = false
                    session = null
                    true
                }
                if (!current) return
                liveness.recordFailure(url)
                lost(error.message ?: "connection failed")
            }
        }
        session = try {
            factory.open(url.url, listener)
        } catch (failure: Throwable) {
            liveness.recordFailure(url)
            scope.launch { lost(failure.message ?: "connection failed") }
            null
        }
    }

    private fun lost(reason: String) {
        subscriptions.values.forEach { it.listener.onConnectionError(url, reason) }
        scheduleReconnect()
    }

    private fun handle(text: String) {
        val message = try {
            JSONArray(text)
        } catch (_: Exception) {
            return
        }
        if (message.length() < 2) return
        val type = message.optString(0)
        if (type in RELAY_MESSAGES) liveness.recordSuccess(url)
        when (type) {
            "EVENT" -> {
                if (message.length() < 3) return
                val subId = message.optString(1)
                val raw = message.opt(2) as? JSONObject ?: return
                val event = runCatching { Event.parse(raw) }.getOrNull() ?: return
                subscriptions[subId]?.listener?.onEvent(url, event)
            }
            "EOSE" -> subscriptions[message.optString(1)]?.listener?.onEose(url)
            "OK" -> {
                val eventId = message.optString(1)
                val accepted = message.optBoolean(2)
                val reason = message.optString(3)
                if (eventId == authEventId && accepted) {
                    synchronized(lock) {
                        subscriptions.values.forEach(::sendReq)
                        pendingEvents.values.forEach(::sendEvent)
                    }
                }
                val waiters = okWaiters.remove(eventId)
                if (accepted) pendingEvents.remove(eventId)
                waiters?.forEach { it(url, accepted, reason) }
            }
            "AUTH" -> {
                val challenge = message.optString(1)
                val current = signer ?: return
                scope.launch {
                    runCatching {
                        val event = Nip42.auth(current, url, challenge, nowSeconds())
                        authEventId = event.id
                        synchronized(lock) { session?.send(Nip42.wire(event)) }
                    }
                }
            }
            "CLOSED" -> {
                val subscription = subscriptions[message.optString(1)] ?: return
                val reason = message.optString(2).ifBlank { "closed" }
                // An auth-gated REQ is resent once AUTH is accepted, so the subscription stays.
                if (!(reason.startsWith(AUTH_REQUIRED) && signer != null)) subscriptions.remove(subscription.id)
                subscription.listener.onClosed(url, reason)
            }
        }
    }

    private fun sendReq(subscription: Subscription) {
        val payload = JSONArray().put("REQ").put(subscription.id)
        subscription.filters.forEach { payload.put(it.toJsonObject()) }
        session?.send(payload.toString())
    }

    private fun sendEvent(event: Event) {
        session?.send(JSONArray().put("EVENT").put(event.toJsonObject()).toString())
    }

    /** Waits out the relay's backoff, never less than [MIN_RECONNECT_DELAY_MS]. */
    private fun scheduleReconnect() {
        if (!trafficEnabled.get()) return
        if (subscriptions.isEmpty() && pendingEvents.isEmpty()) return
        synchronized(lock) {
            if (reconnectJob?.isActive == true || session != null) return
            reconnectJob = scope.launch {
                delay(maxOf(MIN_RECONNECT_DELAY_MS, liveness.backoffRemaining(url)))
                synchronized(lock) {
                    reconnectJob = null
                    if (session == null && trafficEnabled.get()) openLocked()
                }
            }
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val GOING_AWAY = 1001
        const val MIN_RECONNECT_DELAY_MS = 500L
        val RELAY_MESSAGES = setOf("EVENT", "EOSE", "OK", "CLOSED", "AUTH", "NOTICE", "COUNT")
    }
}

internal const val AUTH_REQUIRED = "auth-required:"

internal fun okThreshold(relayCount: Int): Int = if (relayCount <= 2) 1 else 2
