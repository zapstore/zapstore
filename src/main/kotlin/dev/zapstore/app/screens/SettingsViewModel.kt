package dev.zapstore.app.screens

import dev.zapstore.app.SyncInterval
import dev.zapstore.app.Toasts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.DeviceKeyBackup
import dev.zapstore.app.DeviceKeys
import dev.zapstore.app.DeviceSettings
import dev.zapstore.app.R
import dev.zapstore.app.Wallet
import dev.zapstore.app.DeviceState
import dev.zapstore.app.UpdateInterval
import dev.zapstore.app.account.Account
import dev.zapstore.app.account.AccountStore
import dev.zapstore.app.catalog.CatalogSync
import dev.zapstore.app.catalog.SyncStatus
import dev.zapstore.app.transport.NetworkMode
import dev.zapstore.app.transport.NetworkRuntime
import dev.zapstore.app.transport.TorRuntimeState
import dev.zapstore.iolite.CatalogRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.toNpub
import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val status: SyncStatus = SyncStatus(),
    val catalogUrl: String = "",
    val catalogEpoch: Long = 0,
    val appCount: Int = 0,
    val localStorageBytes: Long = 0,
    val networkMode: NetworkMode = NetworkMode.TorOnly,
    val torState: TorRuntimeState = TorRuntimeState.Stopped,
    val deviceState: DeviceState = DeviceState(),
    val account: Account? = null,
    val accountError: String? = null,
    val signingIn: Boolean = false,
    val key: DeviceKeyUi = DeviceKeyUi(),
    val wallet: WalletUi = WalletUi(),
    /** Profiles of the trusted signers that are known; the list itself is [DeviceState.trustedSigners]. */
    val trustedProfiles: Map<String, ProfileRecord> = emptyMap(),
)

/** The NWC wallet row and its paste sheet. */
data class WalletUi(
    val connected: Boolean = false,
    val pasting: Boolean = false,
    val pasteError: String? = null,
)

/** The device key section: who this phone signs as, and the backup and restore steps in flight. */
data class DeviceKeyUi(
    val npub: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val pasting: Boolean = false,
    val pasteError: String? = null,
    val confirmReplace: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    iolite: Iolite,
    private val catalogSync: CatalogSync,
    private val network: NetworkRuntime,
    private val settings: DeviceSettings,
    private val accounts: AccountStore,
    private val measureLocalStorage: () -> Long,
    private val keyBackup: DeviceKeyBackup? = null,
    private val strings: (Int) -> String = { "" },
    devicePubkey: String = "",
    private val wallet: Wallet? = null,
) : ViewModel() {
    private val localStorageBytes = MutableStateFlow(0L)
    private val signIn = MutableStateFlow(SignInState())
    private val key = MutableStateFlow(DeviceKeyUi(npub = devicePubkey.toNpub()))
    private var candidate: String? = null
    private val walletSheet = MutableStateFlow(WalletUi())
    private val walletUi = combine(wallet?.connection ?: flowOf(null), walletSheet) { connection, sheet ->
        sheet.copy(connected = connection != null)
    }

    private val trustedProfiles = settings.state
        .map { it.trustedSigners }
        .distinctUntilChanged()
        .flatMapLatest { pubkeys ->
            if (pubkeys.isEmpty()) flowOf(emptyMap()) else iolite.query(Query.profiles(pubkeys), AppConfig.profileQuery).map { it.items.orEmpty() }
        }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            catalogSync.status,
            iolite.observeCatalogs(),
            iolite.observeApps().map { it.size },
            localStorageBytes,
        ) { status, catalogs, appCount, storageBytes ->
            SettingsSnapshot(status, catalogs.firstOrNull(), appCount, storageBytes)
        },
        network.mode,
        network.torState,
        combine(accounts.account, signIn, settings.state, key) { account, signInState, deviceState, key ->
            AccountSnapshot(account, signInState, deviceState, key)
        },
        walletUi,
    ) { snapshot, mode, torState, (account, signInState, deviceState, key), walletUi ->
        SettingsUiState(
            status = snapshot.status,
            catalogUrl = snapshot.catalog?.relays?.joinToString("\n") { it.url }.orEmpty(),
            catalogEpoch = snapshot.catalog?.epoch ?: 0,
            appCount = snapshot.appCount,
            localStorageBytes = snapshot.localStorageBytes,
            networkMode = mode,
            torState = torState,
            deviceState = deviceState,
            account = account,
            accountError = signInState.error,
            signingIn = signInState.busy,
            key = key,
            wallet = walletUi,
        )
    }.combine(trustedProfiles) { state, profiles -> state.copy(trustedProfiles = profiles) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    init {
        viewModelScope.launch {
            combine(iolite.observeApps(), iolite.observeCatalogs(), catalogSync.status) { _, _, _ -> }
                .collect { refreshLocalStorage() }
        }
    }

    fun setNetworkMode(mode: NetworkMode) = settings.update { it.copy(networkMode = mode) }

    fun sync() = catalogSync.request(force = true)

    fun signIn() {
        if (signIn.value.busy) return
        signIn.value = SignInState(busy = true)
        viewModelScope.launch {
            val error = runCatching { accounts.signIn() }.exceptionOrNull()
            signIn.value = SignInState(error = error?.message)
        }
    }

    fun signOut() = accounts.signOut()

    fun keyNsec(): String = keyBackup?.nsec().orEmpty()

    fun keyCopied() = key.update { it.copy(message = strings(R.string.key_copied)) }

    fun backUpKey() = keyAction(R.string.key_backed_up) { backup ->
        backup.backUp()
        null
    }

    fun restoreFromSigner() = keyAction(null) { backup ->
        val found = backup.fetchBackup() ?: return@keyAction strings(R.string.key_backup_missing)
        offer(backup, found)
    }

    fun openWallet() = walletSheet.update { it.copy(pasting = true, pasteError = null) }

    fun closeWallet() = walletSheet.update { it.copy(pasting = false, pasteError = null) }

    fun connectWallet(input: String) {
        if (wallet?.connect(input) == true) {
            walletSheet.update { it.copy(pasting = false, pasteError = null) }
        } else {
            walletSheet.update { it.copy(pasteError = strings(R.string.wallet_invalid)) }
        }
    }

    fun disconnectWallet() = wallet?.disconnect()

    fun openPaste() = key.update { it.copy(pasting = true, pasteError = null, message = null) }

    fun closePaste() = key.update { it.copy(pasting = false, pasteError = null) }

    fun submitPaste(input: String) {
        val backup = keyBackup ?: return
        val hex = DeviceKeys.parse(input)
        if (hex == null) {
            key.update { it.copy(pasteError = strings(R.string.paste_key_invalid)) }
            return
        }
        key.update { it.copy(pasting = false, pasteError = null) }
        offer(backup, hex)?.let { message -> key.update { it.copy(message = message) } }
    }

    fun confirmReplace() {
        val hex = candidate ?: return
        keyBackup?.restore(hex)
    }

    fun dismissReplace() {
        candidate = null
        key.update { it.copy(confirmReplace = false) }
    }

    /** Asks before replacing; returns a message instead when there is nothing to replace. */
    private fun offer(backup: DeviceKeyBackup, hex: String): String? {
        if (backup.isCurrent(hex)) return strings(R.string.key_already_current)
        candidate = hex
        key.update { it.copy(confirmReplace = true) }
        return null
    }

    private fun keyAction(done: Int?, action: suspend (DeviceKeyBackup) -> String?) {
        val backup = keyBackup ?: return
        if (key.value.busy) return
        key.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = runCatching { action(backup) ?: done?.let(strings) }
                .getOrElse { error -> error.message ?: strings(R.string.key_action_failed) }
            key.update { it.copy(busy = false, message = message ?: it.message) }
        }
    }

    fun setUpdateInterval(interval: UpdateInterval) = settings.update { it.copy(backgroundUpdateInterval = interval) }

    fun setSyncInterval(interval: SyncInterval) = settings.update { it.copy(syncInterval = interval) }

    fun setAnyNetwork(enabled: Boolean) = settings.update { it.copy(backgroundAutoUpdatesAnyNetwork = enabled) }

    fun setBugReports(enabled: Boolean) = settings.update { it.copy(bugReports = enabled) }

    fun removeTrustedSigner(pubkey: String) {
        settings.update { it.copy(trustedSigners = it.trustedSigners - pubkey) }
        Toasts.show(strings(R.string.toast_untrusted))
    }

    fun wipe() {
        viewModelScope.launch {
            catalogSync.wipe()
            network.clearArtiCache()
            refreshLocalStorage()
        }
    }

    private suspend fun refreshLocalStorage() {
        localStorageBytes.value = withContext(Dispatchers.IO) { measureLocalStorage() }
    }

    companion object {
        fun factory(
            iolite: Iolite,
            catalogSync: CatalogSync,
            network: NetworkRuntime,
            settings: DeviceSettings,
            accounts: AccountStore,
            measureLocalStorage: () -> Long,
            keyBackup: DeviceKeyBackup? = null,
            strings: (Int) -> String = { "" },
            devicePubkey: String = "",
            wallet: Wallet? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(iolite, catalogSync, network, settings, accounts, measureLocalStorage, keyBackup, strings, devicePubkey, wallet)
            }
        }
    }
}

private data class SettingsSnapshot(
    val status: SyncStatus,
    val catalog: CatalogRecord?,
    val appCount: Int,
    val localStorageBytes: Long,
)

private data class AccountSnapshot(
    val account: Account?,
    val signIn: SignInState,
    val deviceState: DeviceState,
    val key: DeviceKeyUi,
)

private data class SignInState(val busy: Boolean = false, val error: String? = null)
