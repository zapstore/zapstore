package dev.zapstore.app.screens

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppConfig
import dev.zapstore.app.R
import dev.zapstore.app.ZapstoreApplication
import dev.zapstore.app.install.BlockReason
import dev.zapstore.app.install.InstallEvents
import dev.zapstore.app.install.InstallOrigin
import dev.zapstore.app.install.InstallPlan
import dev.zapstore.app.install.InstallResult
import dev.zapstore.app.install.InstallTurn
import dev.zapstore.app.install.awaitTerminal
import dev.zapstore.app.install.installFailure
import dev.zapstore.app.install.installOrigin
import dev.zapstore.app.install.installPlan
import dev.zapstore.app.install.stageAndCommit
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.ZapRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

data class ZapSummary(
    val count: Int = 0,
    val totalSats: Long = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

data class CommentsState(
    val threads: List<CommentThread> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loading: Boolean = true,
    val error: CommentsError? = null,
)

data class InstallUi(
    val offer: Boolean = false,
    val updating: Boolean = false,
    val installed: Boolean = false,
    val dialog: InstallPlan? = null,
    val busy: Boolean = false,
    val received: Long = 0,
    val total: Long? = null,
    val message: String? = null,
)

data class AppDetailUiState(
    val app: AppRecord? = null,
    /** False until the first SQLite read; afterwards a null [app] means "not in the catalog". */
    val loaded: Boolean = false,
    val author: ProfileRecord? = null,
    val zaps: ZapSummary = ZapSummary(),
    val comments: CommentsState = CommentsState(),
    val install: InstallUi = InstallUi(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModel(
    private val appContext: ZapstoreApplication,
    private val iolite: Iolite,
    private val calls: Call.Factory,
    private val refreshInstalled: () -> Unit,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val appId: String = requireNotNull(savedStateHandle[Routes.APP_ID_ARG])

    private val app: Flow<AppRecord?> = iolite.observeApp(appId)
    private val originTick = MutableStateFlow(0)
    private val originState = MutableStateFlow<InstallOrigin?>(null)
    private val installUi = MutableStateFlow(InstallUi())

    private val author: Flow<ProfileRecord?> = app
        .map { it?.authorPubkey }
        .distinctUntilChanged()
        .flatMapLatest { pubkey ->
            if (pubkey == null) flowOf(null) else iolite.query(Query.profile(pubkey), AppConfig.profileQuery).map { it.items }
        }

    private val zaps: Flow<QueryState<List<ZapRecord>>?> = app
        .map { it?.coordinate }
        .distinctUntilChanged()
        .flatMapLatest { coordinate ->
            if (coordinate == null) flowOf(null) else iolite.query(Query.zaps(coordinate, limit = ZAP_LIMIT), AppConfig.zapQuery)
        }

    private val comments: Flow<QueryState<List<CommentRecord>>?> = app
        .map { it?.coordinate }
        .distinctUntilChanged()
        .flatMapLatest { coordinate ->
            if (coordinate == null) {
                flowOf(null)
            } else {
                iolite.query(Query.comments(coordinate, limit = COMMENT_LIMIT), AppConfig.commentQuery)
            }
        }

    private val commentProfiles: Flow<Map<String, ProfileRecord>> = comments
        .map { state -> state?.items.orEmpty().map(CommentRecord::pubkey).toSet() }
        .distinctUntilChanged()
        .flatMapLatest { pubkeys ->
            if (pubkeys.isEmpty()) {
                flowOf(emptyMap())
            } else {
                iolite.query(Query.profiles(pubkeys), AppConfig.commentProfileQuery).map { it.items }
            }
        }

    private val catalog = combine(app, author, zaps, comments, commentProfiles) { app, author, zaps, comments, commentProfiles ->
        AppDetailUiState(
            app = app,
            loaded = true,
            author = author,
            zaps = ZapSummary(
                count = zaps?.items?.size ?: 0,
                totalSats = zaps?.items?.sumOf(ZapRecord::amountSats) ?: 0,
                loading = zaps?.isLoading ?: false,
                error = zaps?.error?.message,
            ),
            comments = CommentsState(
                threads = threadComments(comments?.items.orEmpty()),
                profiles = commentProfiles,
                loading = comments?.isLoading ?: false,
                error = comments?.error.toCommentsError(),
            ),
        )
    }

    val uiState: StateFlow<AppDetailUiState> = combine(catalog, originState, installUi) { catalog, origin, install ->
        val record = catalog.app
        val known = origin != null
        val installed = origin?.installed == true
        val newer = record != null && known && (!installed || record.versionCode > origin.versionCode)
        catalog.copy(
            install = install.copy(
                offer = newer,
                updating = installed && newer,
                installed = installed && !newer,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppDetailUiState())

    private val stopListening = InstallEvents.listen { result ->
        if (result.packageId != appId) return@listen
        viewModelScope.launch { onInstallResult(result) }
    }

    init {
        viewModelScope.launch {
            combine(app, originTick) { record, _ -> record }
                .flatMapLatest { record ->
                    flow {
                        if (record == null) {
                            emit(null)
                        } else {
                            emit(withContext(Dispatchers.IO) { appContext.installOrigin(record.appId) })
                        }
                    }
                }
                .collect { originState.value = it }
        }
    }

    fun retryComments() {
        iolite.refreshConnections()
    }

    fun prepareInstall() {
        val record = uiState.value.app ?: return
        val origin = originState.value ?: return
        if (installUi.value.busy) return
        val plan = planFor(record, origin, apk = null)
        if (plan is InstallPlan.Blocked) {
            installUi.update { it.copy(dialog = null, message = blockMessage(plan.reason)) }
            return
        }
        installUi.update { it.copy(dialog = plan, message = null) }
    }

    fun dismissInstall() {
        installUi.update { it.copy(dialog = null) }
    }

    fun refreshOrigin() {
        originTick.update { it + 1 }
    }

    fun confirmInstall() {
        val record = uiState.value.app ?: return
        val origin = originState.value ?: return
        if (installUi.value.dialog == null || installUi.value.busy) return
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            installUi.update { it.copy(dialog = null, message = appContext.getString(R.string.install_permission)) }
            return
        }
        installUi.update { it.copy(dialog = null, busy = true, message = null, received = 0, total = null) }
        viewModelScope.launch {
            try {
                val result = InstallTurn.exclusive {
                    awaitTerminal(record.appId) {
                        stageAndCommit(
                            context = appContext,
                            calls = calls,
                            app = record,
                            origin = origin,
                            ourPackage = appContext.packageName,
                            bulk = false,
                        ) { read, total ->
                            installUi.update { it.copy(received = read, total = total) }
                        }
                    }
                }
                if (!result.success) {
                    installUi.update {
                        it.copy(
                            busy = false,
                            received = 0,
                            total = null,
                            message = result.message ?: appContext.getString(R.string.install_timeout),
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                installUi.update {
                    it.copy(busy = false, message = appContext.getString(R.string.install_timeout))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) throw e
                installUi.update {
                    it.copy(busy = false, received = 0, total = null, message = appContext.installFailure(e))
                }
            }
        }
    }

    private fun onInstallResult(result: InstallResult) {
        if (result.awaitingUser) {
            installUi.update { it.copy(busy = false, message = result.message) }
            return
        }
        installUi.update {
            it.copy(
                busy = false,
                received = 0,
                total = null,
                message = if (result.success) null else result.message,
            )
        }
        if (result.success) {
            refreshInstalled()
            originTick.update { it + 1 }
        }
    }

    private fun planFor(record: AppRecord, origin: InstallOrigin, apk: dev.zapstore.app.apk.ApkIdentity?) = installPlan(
        deviceSdk = Build.VERSION.SDK_INT,
        ourPackage = appContext.packageName,
        packageId = record.appId,
        origin = origin,
        listedVersionCode = record.versionCode,
        listedHash = record.apkHash,
        listedCertificate = record.certificateHash,
        apk = apk,
    )

    private fun blockMessage(reason: BlockReason): String = appContext.getString(
        when (reason) {
            BlockReason.Platform -> R.string.install_blocked_platform
            BlockReason.Signer -> R.string.install_blocked_signer
            BlockReason.Downgrade -> R.string.install_downgrade
            BlockReason.MissingFile -> R.string.install_missing
            BlockReason.Banned -> R.string.install_banned
            BlockReason.NotListed -> R.string.install_not_listed
        },
    )

    override fun onCleared() {
        stopListening()
    }

    companion object {
        /** The summary is a recent-activity signal, not a ledger. */
        private const val ZAP_LIMIT = 500
        private const val COMMENT_LIMIT = 50

        fun factory(app: ZapstoreApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AppDetailViewModel(
                    app,
                    app.iolite,
                    app.network.transport.callFactory,
                    app.catalogSync::refreshInstalled,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
