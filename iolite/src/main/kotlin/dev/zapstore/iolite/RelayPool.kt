package dev.zapstore.iolite

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class RelayPool(
    private val factory: WebSocketFactory,
    private val scope: CoroutineScope,
    private val signer: Signer?,
    private val nowSeconds: () -> Long,
) {
    private val connections = ConcurrentHashMap<RelayUrl, RelayConnection>()
    private val trafficEnabled = AtomicBoolean(true)

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

    fun subscribe(
        id: String,
        filters: List<Filter>,
        relays: Set<RelayUrl>,
        onEvent: (RelayUrl, Event) -> Unit,
        onEose: (RelayUrl) -> Unit,
        onState: (RelayUrl, RelayQueryState) -> Unit,
    ): () -> Unit {
        val subscription = Subscription(id, filters, onEvent, onEose, onState)
        relays.forEach { url -> connection(url).add(subscription) }
        return {
            relays.forEach { url -> connections[url]?.remove(id) }
        }
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
            )
        }.also { it.ensureOpen() }
}

private class Subscription(
    val id: String,
    val filters: List<Filter>,
    val onEvent: (RelayUrl, Event) -> Unit,
    val onEose: (RelayUrl) -> Unit,
    val onState: (RelayUrl, RelayQueryState) -> Unit,
)

private class RelayConnection(
    private val url: RelayUrl,
    private val factory: WebSocketFactory,
    private val scope: CoroutineScope,
    private val signer: Signer?,
    private val nowSeconds: () -> Long,
    private val trafficEnabled: AtomicBoolean,
) {
    private val lock = Any()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val okWaiters = ConcurrentHashMap<String, CopyOnWriteArrayList<(RelayUrl, Boolean, String) -> Unit>>()
    private val pendingEvents = ConcurrentHashMap<String, Event>()
    private val generation = AtomicLong(0)
    private var session: WebSocketSession? = null
    private var reconnectJob: Job? = null
    private var authEventId: String? = null
    private var lastError: String? = null
    private var connected = false

    fun add(subscription: Subscription) {
        subscriptions[subscription.id] = subscription
        ensureOpen()
        synchronized(lock) {
            if (connected) sendReq(subscription)
        }
        emitState(RelayConnectionState.Connecting, eose = false)
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
            reconnectJob?.cancel()
            reconnectJob = null
            runCatching { session?.close() }
            session = null
            connected = false
            authEventId = null
        }
        emitState(RelayConnectionState.Disconnected, eose = false, error = reason)
    }

    private fun openLocked() {
        emitState(RelayConnectionState.Connecting, eose = false)
        val listener = object : WebSocketListener {
            override fun onOpen() {
                synchronized(lock) {
                    connected = true
                    lastError = null
                    generation.incrementAndGet()
                    subscriptions.values.forEach(::sendReq)
                    pendingEvents.values.forEach(::sendEvent)
                }
                emitState(RelayConnectionState.Connected, eose = false)
            }

            override fun onMessage(text: String) = handle(text)

            override fun onClosing(code: Int, reason: String) {
                synchronized(lock) {
                    connected = false
                    session = null
                }
                emitState(RelayConnectionState.Disconnected, eose = false, error = reason)
                scheduleReconnect()
            }

            override fun onFailure(error: Throwable) {
                synchronized(lock) {
                    connected = false
                    session = null
                    lastError = error.message
                }
                emitState(RelayConnectionState.Disconnected, eose = false, error = error.message)
                scheduleReconnect()
            }
        }
        session = try {
            factory.open(url.url, listener)
        } catch (failure: Throwable) {
            lastError = failure.message
            emitState(RelayConnectionState.Disconnected, eose = false, error = failure.message)
            scheduleReconnect()
            null
        }
    }

    private fun handle(text: String) {
        val message = try {
            JSONArray(text)
        } catch (_: Exception) {
            return
        }
        if (message.length() < 2) return
        when (message.optString(0)) {
            "EVENT" -> {
                if (message.length() < 3) return
                val subId = message.optString(1)
                val raw = message.opt(2) as? JSONObject ?: return
                val event = runCatching { Event.parse(raw) }.getOrNull() ?: return
                val subscription = subscriptions[subId] ?: return
                subscription.onEvent(url, event)
            }
            "EOSE" -> subscriptions[message.optString(1)]?.onEose(url)
            "OK" -> {
                val eventId = message.optString(1)
                val accepted = message.optBoolean(2)
                val reason = message.optString(3)
                if (eventId == authEventId && accepted) {
                    synchronized(lock) { subscriptions.values.forEach(::sendReq) }
                }
                val waiters = okWaiters.remove(eventId)
                if (accepted) pendingEvents.remove(eventId)
                waiters?.forEach { it(url, accepted, reason) }
            }
            "AUTH" -> {
                val challenge = message.optString(1)
                val current = signer
                if (current == null) {
                    lastError = "auth required"
                    emitState(RelayConnectionState.Connected, eose = false, error = lastError)
                    return
                }
                scope.launch {
                    runCatching {
                        val event = Nip42.auth(current, url, challenge, nowSeconds())
                        authEventId = event.id
                        synchronized(lock) { session?.send(Nip42.wire(event)) }
                    }.onFailure { failure ->
                        lastError = failure.message
                        emitState(RelayConnectionState.Connected, eose = false, error = lastError)
                    }
                }
            }
            "CLOSED" -> {
                val subId = message.optString(1)
                lastError = message.optString(2).ifBlank { "closed" }
                subscriptions[subId]?.onEose(url)
                emitState(RelayConnectionState.Connected, eose = true, error = lastError)
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

    private fun scheduleReconnect() {
        if (!trafficEnabled.get()) return
        if (subscriptions.isEmpty() && pendingEvents.isEmpty()) return
        synchronized(lock) {
            if (reconnectJob?.isActive == true || session != null) return
            reconnectJob = scope.launch {
                delay(500)
                synchronized(lock) {
                    reconnectJob = null
                    if (session == null && trafficEnabled.get()) openLocked()
                }
            }
        }
    }

    private fun emitState(connection: RelayConnectionState, eose: Boolean, error: String? = lastError) {
        val state = RelayQueryState(generation.get(), connection, eose, error)
        subscriptions.values.forEach { it.onState(url, state) }
    }
}

internal fun okThreshold(relayCount: Int): Int = if (relayCount <= 2) 1 else 2
