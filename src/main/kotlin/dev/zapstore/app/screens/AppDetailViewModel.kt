package dev.zapstore.app.screens

import kotlinx.coroutines.Job
import dev.zapstore.app.install.StagePhase
import dev.zapstore.app.Toasts
import dev.zapstore.iolite.Social
import dev.zapstore.iolite.Kinds
import dev.zapstore.iolite.Bolt11
import dev.zapstore.iolite.NwcException
import dev.zapstore.app.Lnurl
import dev.zapstore.app.ZapException
import dev.zapstore.app.ZapFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds
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
import dev.zapstore.app.catalog.otherStore
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
import dev.zapstore.app.install.message
import dev.zapstore.app.install.stageAndCommit
import dev.zapstore.iolite.AppCoordinate
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.StackRecord
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
    /** Biggest zappers by total, at most [TOP_ZAPPERS]. */
    val zappers: List<String> = emptyList(),
    val recipient: ZapRecipient? = null,
    val profiles: Map<String, ProfileRecord> = emptyMap(),
)

/** Who a zap pays. [catalog] when the app has no C1 owner and the catalog that lists it is paid. */
data class ZapRecipient(val pubkey: String, val catalog: Boolean)

const val TOP_ZAPPERS = 5

data class CommentsState(
    val threads: List<CommentThread> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loading: Boolean = true,
    val error: CommentsError? = null,
)

/** Where an install started from the app page is, as the bottom button shows it. */
enum class InstallPhase { Idle, Queued, Downloading, Paused, Verifying, Installing, Cancelled, Failed }

data class InstallUi(
    val offer: Boolean = false,
    val updating: Boolean = false,
    val installed: Boolean = false,
    val dialog: InstallPlan? = null,
    val phase: InstallPhase = InstallPhase.Idle,
    val received: Long = 0,
    val total: Long? = null,
    /** While [InstallPhase.Installing]: false when Android shows its own prompt. */
    val silent: Boolean = true,
    /** What went wrong, while [InstallPhase.Failed]. */
    val error: String? = null,
    /** The name of the store that keeps this app current, when it is not Zapstore. */
    val otherStore: String? = null,
) {
    val busy: Boolean get() = phase in BUSY_PHASES
}

private val BUSY_PHASES = setOf(InstallPhase.Queued, InstallPhase.Downloading, InstallPhase.Verifying, InstallPhase.Installing)

/** Writing about this app as the signed-in account: a comment or a report. */
data class SocialUi(
    val signedIn: Boolean = false,
    val posting: Boolean = false,
    val error: String? = null,
    /** Bumped after a comment is accepted, so the field clears. */
    val posted: Int = 0,
    val reported: Boolean = false,
    /** The app has a current C1 owner, so it can be zapped. */
    val zappable: Boolean = false,
    val walletConnected: Boolean = false,
    val zapping: Boolean = false,
    val zapError: String? = null,
    /** Sats of the last zap the wallet paid. */
    val zapped: Long? = null,
)

/** The signed-in account's public stacks, for adding this app. Null while signed out. */
data class UserStacksUi(
    val stacks: List<StackRecord> = emptyList(),
    val loading: Boolean = false,
    /** Identifier of the stack being written, or [NEW_STACK] while one is created. */
    val busy: String? = null,
    val error: String? = null,
    /** Bumped after a new stack is published, so the name field closes. */
    val created: Int = 0,
) {
    companion object {
        const val NEW_STACK = "+new"
    }
}

/** One public stack per curator that lists this app, newest first, with the curators' profiles. */
data class StackedByUi(
    val stacks: List<StackRecord> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
)

data class AppDetailUiState(
    val app: AppRecord? = null,
    /** False until the first SQLite read; afterwards a null [app] means "not in the catalog". */
    val loaded: Boolean = false,
    val author: ProfileRecord? = null,
    val zaps: ZapSummary = ZapSummary(),
    val comments: CommentsState = CommentsState(),
    val install: InstallUi = InstallUi(),
    val social: SocialUi = SocialUi(),
    val userStacks: UserStacksUi? = null,
    val stackedBy: StackedByUi = StackedByUi(),
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
    private var installJob: Job? = null

    private val author: Flow<ProfileRecord?> = app
        .map { it?.authorPubkey }
        .distinctUntilChanged()
        .flatMapLatest { pubkey ->
            if (pubkey == null) flowOf(null) else iolite.query(Query.profile(pubkey), AppConfig.profileQuery).map { it.items }
        }

    private val zaps: Flow<QueryState<List<ZapRecord>>?> = app
        .map { it?.anchors }
        .distinctUntilChanged()
        .flatMapLatest { anchors ->
            if (anchors == null) flowOf(null) else iolite.query(Query.zaps(anchors, limit = ZAP_LIMIT), AppConfig.zapQuery)
        }

    private val comments: Flow<QueryState<List<CommentRecord>>?> = app
        .map { it?.anchors }
        .distinctUntilChanged()
        .flatMapLatest { anchors ->
            if (anchors == null) {
                flowOf(null)
            } else {
                iolite.query(Query.comments(anchors, limit = COMMENT_LIMIT), AppConfig.commentQuery)
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

    private val zapProfiles: Flow<Map<String, ProfileRecord>> = combine(app.map { it?.zapRecipient }, zaps) { recipient, zaps ->
        topZappers(zaps?.items.orEmpty()).toSet() + listOfNotNull(recipient)
    }
        .distinctUntilChanged()
        .flatMapLatest { pubkeys ->
            if (pubkeys.isEmpty()) flowOf(emptyMap()) else iolite.query(Query.profiles(pubkeys), AppConfig.commentProfileQuery).map { it.items }
        }

    private val catalog = combine(app, author, combine(zaps, zapProfiles, ::Pair), comments, commentProfiles) { app, author, (zaps, zapProfiles), comments, commentProfiles ->
        AppDetailUiState(
            app = app,
            loaded = true,
            author = author,
            zaps = ZapSummary(
                count = zaps?.items?.size ?: 0,
                totalSats = zaps?.items?.sumOf(ZapRecord::amountSats) ?: 0,
                loading = zaps?.isLoading ?: false,
                error = zaps?.error?.message,
                zappers = topZappers(zaps?.items.orEmpty()),
                recipient = app?.zapRecipient?.let { ZapRecipient(it, catalog = app.proofPubkey == null) },
                profiles = zapProfiles,
            ),
            comments = CommentsState(
                threads = threadComments(comments?.items.orEmpty()),
                profiles = commentProfiles,
                loading = comments?.isLoading ?: false,
                error = comments?.error.toCommentsError(),
            ),
        )
    }

    private val stackedBy: Flow<StackedByUi> = app
        .map { app -> app?.let { it.anchors + AppCoordinate(AppConfig.LEGACY_CATALOG_PUBKEY, it.appId) }?.distinct() }
        .distinctUntilChanged()
        .flatMapLatest { anchors ->
            if (anchors == null) flowOf(emptyList())
            else iolite.query(Query.stackedBy(anchors, STACKED_BY_LIMIT), AppConfig.stackedByQuery).map { state -> state.items.distinctBy { it.pubkey } }
        }
        .flatMapLatest { stacks ->
            val curators = stacks.map(StackRecord::pubkey).toSet()
            if (curators.isEmpty()) flowOf(StackedByUi())
            else iolite.query(Query.profiles(curators), AppConfig.commentProfileQuery).map { StackedByUi(stacks, it.items) }
        }

    private val lnurl = Lnurl(calls)
    private val social = MutableStateFlow(SocialUi())
    private val socialUi = combine(social, appContext.account.account, appContext.wallet.connection, app) { social, account, wallet, app ->
        social.copy(signedIn = account != null, walletConnected = wallet != null, zappable = app?.zapRecipient != null)
    }

    private val stackWrite = MutableStateFlow(UserStacksUi())
    private val userStacks: Flow<UserStacksUi?> = appContext.account.account
        .map { it?.pubkey }
        .distinctUntilChanged()
        .flatMapLatest { pubkey ->
            if (pubkey == null) flowOf(null)
            else combine(iolite.query(Query.stacks(pubkey), AppConfig.authorStacksQuery), stackWrite) { state, write ->
                write.copy(stacks = state.items.filter { it.apps.isNotEmpty() }, loading = state.items.isEmpty() && state.isLoading)
            }
        }

    val uiState: StateFlow<AppDetailUiState> = combine(
        combine(catalog, stackedBy) { catalog, stackedBy -> catalog.copy(stackedBy = stackedBy) },
        originState,
        installUi,
        socialUi,
        userStacks,
    ) { catalog, origin, install, social, stacks ->
        val record = catalog.app
        val known = origin != null
        val installed = origin?.installed == true
        val newer = record != null && known && (!installed || record.versionCode > origin.versionCode)
        catalog.copy(
            social = social,
            userStacks = stacks,
            install = install.copy(
                offer = newer,
                updating = installed && newer,
                installed = installed && !newer,
                otherStore = origin?.let { storeLabel(otherStore(it.installingPackage, it.updateOwner, appContext.packageName)) },
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

    /** Publishes a top-level comment, or a reply to [parent], as the signed-in account. */
    fun postComment(text: String, parent: CommentRecord? = null) {
        val content = text.trim()
        val anchor = uiState.value.app?.catalogAnchor ?: return
        if (content.isEmpty() || social.value.posting) return
        social.update { it.copy(posting = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val signer = appContext.account.signer() ?: error(appContext.getString(R.string.sign_in_first))
                iolite.publish(Kinds.Comment, Social.commentTags(anchor, parent), content, signer, relays = AppConfig.commentWriteRelays)
            }
            social.update { state ->
                result.fold(
                    onSuccess = {
                        Toasts.show(appContext.getString(if (parent == null) R.string.toast_comment_posted else R.string.toast_reply_posted))
                        state.copy(posting = false, posted = state.posted + 1)
                    },
                    onFailure = { state.copy(posting = false, error = it.message ?: appContext.getString(R.string.post_failed)) },
                )
            }
        }
    }

    /** A NIP-56 report on the app, sent only to the catalog relay. */
    fun report(type: String, note: String) {
        val anchor = uiState.value.app?.catalogAnchor ?: return
        if (social.value.posting) return
        social.update { it.copy(posting = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val signer = appContext.account.signer() ?: error(appContext.getString(R.string.sign_in_first))
                iolite.publish(Kinds.Report, Social.reportTags(anchor, type), note.trim(), signer, relays = setOf(AppConfig.catalogRelay))
            }
            social.update { state ->
                result.fold(
                    onSuccess = { state.copy(posting = false, reported = true) },
                    onFailure = { state.copy(posting = false, error = it.message ?: appContext.getString(R.string.post_failed)) },
                )
            }
        }
    }

    private val storeLabels = HashMap<String, String>()

    private fun storeLabel(packageName: String?): String? = packageName?.let { name ->
        storeLabels.getOrPut(name) {
            runCatching {
                val pm = appContext.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
            }.getOrDefault(name)
        }
    }

    /** Adds this app to [stack], or removes it when the stack already lists it. */
    fun toggleInStack(stack: StackRecord) {
        val app = uiState.value.app ?: return
        val removing = app.appId in stack.appIds
        val apps = if (removing) stack.apps.filter { it.appId != app.appId }
        else stack.apps + AppCoordinate(app.eventPubkey, app.appId)
        val done = appContext.getString(if (removing) R.string.toast_stack_removed else R.string.toast_stack_added, stack.name)
        writeStack(stack.identifier, stack.identifier, stack.name, apps, done)
    }

    /** Creates a public stack named [name] holding this app. */
    fun createStack(name: String) {
        val app = uiState.value.app ?: return
        val title = name.trim().takeIf { it.isNotEmpty() } ?: return
        val taken = uiState.value.userStacks?.stacks.orEmpty().map(StackRecord::identifier).toSet()
        val base = stackIdentifier(title)
        val identifier = generateSequence(2) { it + 1 }.map { "$base-$it" }.let { sequenceOf(base) + it }.first { it !in taken }
        writeStack(UserStacksUi.NEW_STACK, identifier, title, listOf(AppCoordinate(app.eventPubkey, app.appId)), appContext.getString(R.string.toast_stack_created, title))
    }

    private fun writeStack(busy: String, identifier: String, name: String, apps: List<AppCoordinate>, done: String) {
        if (stackWrite.value.busy != null) return
        stackWrite.update { it.copy(busy = busy, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val signer = appContext.account.signer() ?: error(appContext.getString(R.string.sign_in_first))
                iolite.setStack(signer, identifier, name, apps)
            }
            if (result.isSuccess) Toasts.show(done)
            stackWrite.update {
                it.copy(
                    busy = null,
                    error = result.exceptionOrNull()?.let { e -> e.message ?: appContext.getString(R.string.post_failed) },
                    created = if (result.isSuccess && busy == UserStacksUi.NEW_STACK) it.created + 1 else it.created,
                )
            }
        }
    }

    fun clearReport() = social.update { it.copy(reported = false, error = null) }

    /**
     * Zaps [sats] through the connected wallet to the app's C1 owner, or else to the catalog that
     * lists it. The zap request names the catalog coordinate and is signed by the account; the
     * invoice must match the amount.
     */
    fun zap(sats: Long, note: String) {
        val app = uiState.value.app ?: return
        val anchor = app.catalogAnchor ?: return
        val owner = app.zapRecipient ?: return
        if (sats <= 0 || social.value.zapping) return
        social.update { it.copy(zapping = true, zapError = null, zapped = null) }
        viewModelScope.launch {
            val result = runCatching {
                val connection = appContext.wallet.connection.value ?: error(appContext.getString(R.string.zap_no_wallet))
                val signer = appContext.account.signer() ?: error(appContext.getString(R.string.sign_in_first))
                val address = withTimeoutOrNull(PROFILE_WAIT) {
                    iolite.query(Query.profile(owner), AppConfig.profileQuery).first { it.items?.lud16 != null || it.isTerminal }.items?.lud16
                } ?: throw ZapException(ZapFailure.NoAddress)
                val pay = lnurl.pay(address)
                val msat = sats * 1_000
                if (msat < pay.minMsat || msat > pay.maxMsat) throw ZapException(ZapFailure.Amount)
                val request = signer.sign(
                    System.currentTimeMillis() / 1_000,
                    Kinds.ZapRequest,
                    Social.zapRequestTags(anchor, owner, msat, AppConfig.zapRelays),
                    note.trim(),
                )
                val invoice = lnurl.invoice(pay, msat, request)
                if (Bolt11.amountSats(invoice) != sats) throw ZapException(ZapFailure.Invoice)
                iolite.walletRequest(connection, "pay_invoice", JSONObject().put("invoice", invoice))
            }
            social.update { state ->
                result.fold(
                    onSuccess = { state.copy(zapping = false, zapped = sats) },
                    onFailure = { state.copy(zapping = false, zapError = zapMessage(it)) },
                )
            }
        }
    }

    fun clearZap() = social.update { it.copy(zapError = null, zapped = null) }

    private fun zapMessage(error: Throwable): String = when (error) {
        is ZapException -> appContext.getString(
            when (error.failure) {
                ZapFailure.NoAddress -> R.string.zap_no_address
                ZapFailure.NoZaps -> R.string.zap_not_supported
                ZapFailure.Amount -> R.string.zap_amount_range
                ZapFailure.Invoice -> R.string.zap_bad_invoice
                ZapFailure.Unreachable -> R.string.zap_unreachable
            },
        )
        is NwcException -> if (error.code == "TIMEOUT") {
            appContext.getString(R.string.zap_wallet_timeout)
        } else {
            appContext.getString(R.string.zap_wallet_error, error.message.orEmpty().ifBlank { error.code })
        }
        else -> error.message ?: appContext.getString(R.string.zap_failed)
    }

    fun retryComments() {
        iolite.refreshConnections()
    }

    /** Install or Update: the trust sheet for a first install from an untrusted author or a takeover, otherwise straight on. */
    fun prepareInstall() {
        val record = uiState.value.app ?: return
        val origin = originState.value ?: return
        if (installUi.value.busy) return
        val plan = planFor(record, origin, apk = null)
        if (plan is InstallPlan.Blocked) {
            fail(blockMessage(plan.reason))
            return
        }
        val trusted = plan is InstallPlan.FirstInstall && record.authorPubkey in appContext.settings.state.value.trustedSigners
        if (plan is InstallPlan.Silent || trusted) startInstall() else installUi.update { it.copy(dialog = plan) }
    }

    fun dismissInstall() {
        installUi.update { it.copy(dialog = null) }
    }

    fun refreshOrigin() {
        originTick.update { it + 1 }
    }

    /** [alwaysTrust] adds the verified author to the trusted signers, so their next first install skips the sheet. */
    fun confirmInstall(alwaysTrust: Boolean = false) {
        val record = uiState.value.app ?: return
        if (installUi.value.dialog == null || installUi.value.busy) return
        val author = record.authorPubkey
        if (alwaysTrust && author != null && author !in appContext.settings.state.value.trustedSigners) {
            appContext.settings.update { it.copy(trustedSigners = it.trustedSigners + author) }
            val name = uiState.value.author?.displayNameOrNpub ?: appContext.getString(R.string.this_developer)
            Toasts.show(appContext.getString(R.string.toast_trusted, name))
        }
        installUi.update { it.copy(dialog = null) }
        startInstall()
    }

    /** Starts, resumes, or retries. A paused download continues from its partial file. */
    fun startInstall() {
        val record = uiState.value.app ?: return
        val origin = originState.value ?: return
        if (installUi.value.busy) return
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            fail(appContext.getString(R.string.install_permission))
            return
        }
        installUi.update { it.copy(phase = InstallPhase.Queued, error = null, silent = true) }
        installJob = viewModelScope.launch {
            try {
                val result = InstallTurn.exclusive {
                    installUi.update { it.copy(phase = InstallPhase.Downloading) }
                    awaitTerminal(record.appId) {
                        stageAndCommit(
                            context = appContext,
                            calls = calls,
                            app = record,
                            origin = origin,
                            ourPackage = appContext.packageName,
                            bulk = false,
                            proofOf = iolite::proof,
                            onPhase = { stage ->
                                installUi.update {
                                    when (stage) {
                                        StagePhase.Verifying -> it.copy(phase = InstallPhase.Verifying)
                                        is StagePhase.Committing -> it.copy(phase = InstallPhase.Installing, silent = stage.silent)
                                    }
                                }
                            },
                        ) { read, total ->
                            installUi.update { it.copy(received = read, total = total) }
                        }
                    }
                }
                finish(result)
            } catch (e: TimeoutCancellationException) {
                fail(appContext.getString(R.string.install_timeout))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) throw e
                fail(appContext.installFailure(e))
            }
        }
    }

    /** Stops the download and keeps what arrived. */
    fun pauseInstall() {
        if (installUi.value.phase != InstallPhase.Downloading) return
        installUi.update { it.copy(phase = InstallPhase.Paused) }
        installJob?.cancel()
    }

    /** The error was shown; back to the normal button. */
    fun dismissError() {
        installUi.update { it.copy(phase = InstallPhase.Idle, error = null) }
    }

    private fun finish(result: InstallResult) {
        when {
            result.success -> {
                installUi.update { it.copy(phase = InstallPhase.Idle, received = 0, total = null) }
                refreshInstalled()
                originTick.update { it + 1 }
            }
            result.cancelled -> installUi.update { it.copy(phase = InstallPhase.Cancelled, received = 0, total = null) }
            else -> fail(result.message ?: appContext.getString(R.string.install_timeout))
        }
    }

    private fun fail(message: String) {
        installUi.update { it.copy(phase = InstallPhase.Failed, error = message, dialog = null, received = 0, total = null) }
    }

    private fun onInstallResult(result: InstallResult) {
        if (result.awaitingUser) {
            if (result.message != null) {
                installJob?.cancel()
                fail(result.message)
            } else {
                installUi.update { if (it.busy) it.copy(phase = InstallPhase.Installing, silent = false) else it }
            }
            return
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

    private fun blockMessage(reason: BlockReason): String = appContext.getString(reason.message)

    override fun onCleared() {
        stopListening()
    }

    companion object {
        /** The summary is a recent-activity signal, not a ledger. */
        private const val ZAP_LIMIT = 500
        private const val COMMENT_LIMIT = 50
        private const val STACKED_BY_LIMIT = 50
        private val PROFILE_WAIT = 15.seconds

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

/** Lowercase letters and digits joined by `-`, at most 48 characters. */
internal fun stackIdentifier(name: String): String =
    name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("-").take(48).trimEnd('-')
        .ifEmpty { "stack" }

private fun topZappers(zaps: List<ZapRecord>): List<String> =
    zaps.groupBy(ZapRecord::pubkey)
        .mapValues { (_, list) -> list.sumOf(ZapRecord::amountSats) }
        .entries.sortedByDescending { it.value }
        .take(TOP_ZAPPERS)
        .map { it.key }
