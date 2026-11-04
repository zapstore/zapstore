package dev.zapstore.app

import android.content.Context
import dev.zapstore.app.transport.NetworkMode
import dev.zapstore.iolite.DeviceEvents
import dev.zapstore.iolite.Iolite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONObject

/** How often background updates run. [Never] turns them off. */
enum class UpdateInterval(val hours: Int) {
    Never(0),
    Every6Hours(6),
    Every12Hours(12),
    Daily(24),
    Weekly(168),
    ;

    companion object {
        fun fromHours(hours: Int): UpdateInterval = entries.firstOrNull { it.hours == hours } ?: Daily
    }
}

/**
 * How often the catalog is fetched. Android runs background work at most every 15 minutes, so
 * [FiveMinutes] syncs that often only while Zapstore is open.
 */
enum class SyncInterval(val minutes: Int) {
    FiveMinutes(5),
    FifteenMinutes(15),
    Hourly(60),
    SixHours(360),
    TwelveHours(720),
    Daily(1440),
    ;

    companion object {
        fun fromMinutes(minutes: Int): SyncInterval = entries.firstOrNull { it.minutes == minutes } ?: FifteenMinutes
    }
}

/** Plaintext of `zapstore-device-state`. The NWC string never goes here. */
data class DeviceState(
    val backgroundUpdateInterval: UpdateInterval = UpdateInterval.Daily,
    val backgroundAutoUpdatesAnyNetwork: Boolean = false,
    val networkMode: NetworkMode = NetworkMode.TorOnly,
    val bugReports: Boolean = true,
    val syncInterval: SyncInterval = SyncInterval.FifteenMinutes,
) {
    val backgroundAutoUpdates: Boolean get() = backgroundUpdateInterval != UpdateInterval.Never

    fun toJson(base: JSONObject = JSONObject()): JSONObject = base.apply {
        put("backgroundAutoUpdatesEnabled", backgroundAutoUpdates)
        put("backgroundAutoUpdatesIntervalHours", backgroundUpdateInterval.hours)
        put("backgroundAutoUpdatesAnyNetwork", backgroundAutoUpdatesAnyNetwork)
        put("networkMode", if (networkMode == NetworkMode.DirectOnly) "direct" else "tor")
        put("bugReports", bugReports)
        put("catalogSyncIntervalMinutes", syncInterval.minutes)
    }

    companion object {
        /** A missing field keeps its default; Flutter wrote only the two booleans, so it has no interval. */
        fun fromJson(json: JSONObject, defaults: DeviceState = DeviceState()): DeviceState = DeviceState(
            backgroundUpdateInterval = when {
                json.has("backgroundAutoUpdatesIntervalHours") -> UpdateInterval.fromHours(json.optInt("backgroundAutoUpdatesIntervalHours"))
                !json.has("backgroundAutoUpdatesEnabled") -> defaults.backgroundUpdateInterval
                !json.optBoolean("backgroundAutoUpdatesEnabled") -> UpdateInterval.Never
                defaults.backgroundAutoUpdates -> defaults.backgroundUpdateInterval
                else -> UpdateInterval.Daily
            },
            backgroundAutoUpdatesAnyNetwork = json.optBoolean(
                "backgroundAutoUpdatesAnyNetwork",
                defaults.backgroundAutoUpdatesAnyNetwork,
            ),
            networkMode = when (json.optString("networkMode")) {
                "direct" -> NetworkMode.DirectOnly
                "tor" -> NetworkMode.TorOnly
                else -> defaults.networkMode
            },
            bugReports = json.optBoolean("bugReports", defaults.bugReports),
            syncInterval = if (json.has("catalogSyncIntervalMinutes")) {
                SyncInterval.fromMinutes(json.optInt("catalogSyncIntervalMinutes"))
            } else {
                defaults.syncInterval
            },
        )
    }
}

/**
 * Device settings. SharedPreferences is the copy read at startup, so Tor can start before the
 * database opens. `zapstore-device-state` is the copy on the relay; it is published a moment
 * after a change, and an incoming one (a restored device key) overwrites the local copy.
 */
class DeviceSettings(
    context: Context,
    private val scope: CoroutineScope,
    defaultMode: NetworkMode,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { stored ->
        if (stored.contains(KEY_SIGNERS)) stored.edit().remove(KEY_SIGNERS).apply()
    }
    private val _state = MutableStateFlow(load(defaultMode))
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /** The last event content, so fields this version does not know survive a rewrite. */
    private var lastRemote = JSONObject()
    private var pending: Job? = null
    private var iolite: Iolite? = null

    /** Follows the stored event. Call once the database is open. */
    fun attach(iolite: Iolite) {
        this.iolite = iolite
        scope.launch {
            iolite.observeDevicePreference(DeviceEvents.STATE).filterNotNull().collect { plaintext ->
                val json = runCatching { JSONObject(plaintext) }.getOrNull() ?: return@collect
                lastRemote = json
                val incoming = DeviceState.fromJson(json, _state.value)
                if (incoming != _state.value && pending?.isActive != true) save(incoming)
            }
        }
    }

    fun update(change: (DeviceState) -> DeviceState) {
        val next = change(_state.value)
        if (next == _state.value) return
        save(next)
        pending?.cancel()
        pending = scope.launch {
            delay(PUBLISH_DELAY_MS)
            val target = iolite ?: return@launch
            val content = next.toJson(JSONObject(lastRemote.toString())).toString()
            runCatching { target.publishDevicePreference(DeviceEvents.STATE, content) }
                .onFailure { AppLog.w(TAG, "device state publish failed", it) }
        }
    }

    private fun save(state: DeviceState) {
        prefs.edit()
            .putString(KEY_MODE, state.networkMode.name)
            .putInt(KEY_INTERVAL, state.backgroundUpdateInterval.hours)
            .putBoolean(KEY_ANY_NETWORK, state.backgroundAutoUpdatesAnyNetwork)
            .putBoolean(KEY_REPORTS, state.bugReports)
            .putInt(KEY_SYNC, state.syncInterval.minutes)
            .apply()
        _state.value = state
    }

    private fun load(defaultMode: NetworkMode) = DeviceState(
        backgroundUpdateInterval = when {
            prefs.contains(KEY_INTERVAL) -> UpdateInterval.fromHours(prefs.getInt(KEY_INTERVAL, UpdateInterval.Daily.hours))
            !prefs.getBoolean(KEY_AUTO, true) -> UpdateInterval.Never
            else -> UpdateInterval.Daily
        },
        backgroundAutoUpdatesAnyNetwork = prefs.getBoolean(KEY_ANY_NETWORK, false),
        networkMode = NetworkMode.fromStored(prefs.getString(KEY_MODE, defaultMode.name)),
        bugReports = prefs.getBoolean(KEY_REPORTS, true),
        syncInterval = SyncInterval.fromMinutes(prefs.getInt(KEY_SYNC, SyncInterval.FifteenMinutes.minutes)),
    )

    private companion object {
        const val TAG = "DeviceSettings"
        const val PREFS = "network"
        const val KEY_MODE = "mode"
        /** Before the interval: a boolean, read once to carry an off switch over. */
        const val KEY_AUTO = "background_auto_updates"
        const val KEY_INTERVAL = "background_update_interval_hours"
        const val KEY_ANY_NETWORK = "background_any_network"
        /** Leftover from the always-trust list. Dropped once; no longer read. */
        const val KEY_SIGNERS = "trusted_signers"
        const val KEY_REPORTS = "bug_reports"
        const val KEY_SYNC = "catalog_sync_interval_minutes"
        const val PUBLISH_DELAY_MS = 1_500L
    }
}
