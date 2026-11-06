package dev.zapstore.app.screens

import dev.zapstore.app.Toasts
import dev.zapstore.iolite.Social
import dev.zapstore.iolite.Kinds
import dev.zapstore.iolite.Bolt11
import dev.zapstore.iolite.Crypto
import dev.zapstore.iolite.LocalSigner
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
import dev.zapstore.app.components.VersionStatus
import dev.zapstore.app.AppConfig
import dev.zapstore.app.AppLog
import dev.zapstore.app.R
import dev.zapstore.app.ZapstoreApplication
import dev.zapstore.app.catalog.InstalledApp
import dev.zapstore.app.catalog.otherStore
import dev.zapstore.app.install.BlockReason
import dev.zapstore.app.install.InstallOrigin
import dev.zapstore.app.install.InstallPlan
import dev.zapstore.app.install.InstallTask
import dev.zapstore.app.install.TaskPhase
import dev.zapstore.app.install.installOrigin
import dev.zapstore.app.install.installPlan
import dev.zapstore.app.install.message
import dev.zapstore.iolite.AppCoordinate
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CertificateProof
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.StackRecord
import dev.zapstore.iolite.Trust
import dev.zapstore.iolite.ZapRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

data class ZapSummary(
    val count: Int = 0,
    val totalSats: Long = 0,
    val loading: Boolean = true,
    val error: String? = null,
    /** Biggest zappers by total, at most [TOP_ZAPPERS]. */
    val zappers: List<Zapper> = emptyList(),
    /** Everyone who zapped, which can be more than [zappers] holds. */
    val zapperCount: Int = zappers.size,
    val recipient: ZapRecipient? = null,
    val profiles: Map<String, ProfileRecord> = emptyMap(),
)

/** Who a zap pays. [catalog] when the app has no C1 owner and the catalog that lists it is paid. */
data class ZapRecipient(val pubkey: String, val catalog: Boolean)

/** One person's zaps to the app, summed. */
data class Zapper(val pubkey: String, val sats: Long)

const val TOP_ZAPPERS = 10
private const val TAG = "AppDetail"
private const val TRUST_FOLLOWERS = 5

/** Vertex answers in a few seconds; a person waiting on a sheet should not wait for the library's 30. */
private val TRUST_TIMEOUT = 15.seconds

/** Comments and zaps as the page shows them: one feed, built from [threads] and [zaps] by [feedEntries]. */
data class CommentsState(
    /** Comments on a coordinate of a known pubkey, threaded. */
    val threads: List<CommentThread> = emptyList(),
    /** Comments on this app id from any other coordinate, such as another catalog's, threaded. Zaps have no such list. */
    val other: List<CommentThread> = emptyList(),
    val zaps: List<ZapRecord> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loading: Boolean = true,
    val error: CommentsError? = null,
)

/** Where an install started from the app page is, as the bottom button shows it. */
enum class InstallPhase { Idle, Queued, Downloading, Paused, Verifying, Installing, Confirm, Failed }

/** An account named on the first-install sheet. [name] is what the catalog knew, often blank. */
data class TrustPerson(val pubkey: String, val name: String = "")

/**
 * Who follows the publisher, shown on the first-install sheet. Only a publisher with a C1 identity
 * has any of this, and Download is never held back for it. It starts as the catalog's list and
 * becomes [personal] when the signed-in user asks for their own point of view.
 */
data class TrustUi(
    val followers: List<TrustPerson> = emptyList(),
    /** The publisher's own key is a known leak. */
    val leaked: Boolean = false,
    /** Everything below is from the signed-in user's point of view. */
    val personal: Boolean = false,
    /** Accounts the user follows that zapped this app. */
    val zappers: List<TrustPerson> = emptyList(),
    val youFollow: Boolean = false,
    val youZapped: Boolean = false,
    /** The publisher has a C1 identity, so the user's own check can be asked for. Signed out, asking opens sign-in first. */
    val canPersonalize: Boolean = false,
    val checking: Boolean = false,
    val failed: Boolean = false,
    val profiles: Map<String, ProfileRecord> = emptyMap(),
) {
    val hasSignals: Boolean get() = leaked || followers.isNotEmpty() || zappers.isNotEmpty() || youFollow || youZapped

    /** Something to show: signals, a check to offer, or a finished check that found nobody. */
    val hasContent: Boolean get() = hasSignals || canPersonalize || personal
}

data class InstallUi(
    val trust: TrustUi = TrustUi(),
    val offer: Boolean = false,
    val updating: Boolean = false,
    val installed: Boolean = false,
    val dialog: InstallPlan? = null,
    val phase: InstallPhase = InstallPhase.Idle,
    val received: Long = 0,
    val total: Long? = null,
    /** While [InstallPhase.Installing]: false when Android shows its own dialog. */
    val silent: Boolean = true,
    /** What went wrong, while [InstallPhase.Failed]. */
    val error: String? = null,
    /** The name of the store that keeps this app current, when it is not Zapstore. */
    val otherStore: String? = null,
    /** The installed copy shares no certificate with the listing, so no update is offered. */
    val signedDifferently: Boolean = false,
) {
    val busy: Boolean get() = phase in BUSY_PHASES

    /** There is an install to end: anything in progress, including a paused download. */
    val cancellable: Boolean get() = busy || phase == InstallPhase.Paused

    /** Icon inside the Latest release version pill: install, update, or up to date. */
    val versionStatus: VersionStatus?
        get() = when {
            updating -> VersionStatus.Update
            installed && !signedDifferently -> VersionStatus.Current
            offer -> VersionStatus.Install
            else -> null
        }
}

private val BUSY_PHASES = setOf(InstallPhase.Queued, InstallPhase.Downloading, InstallPhase.Verifying, InstallPhase.Installing, InstallPhase.Confirm)

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
    /** Set instead of [zapped] when no wallet is connected: the invoice is shown for the user to pay by hand. */
    val invoice: String? = null,
    val signingIn: Boolean = false,
    val signInError: String? = null,
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
    /** Profile for the header byline: the proof owner, the listing signer, or the indexer. */
    val author: ProfileRecord? = null,
    val zaps: ZapSummary = ZapSummary(),
    val comments: CommentsState = CommentsState(),
    val install: InstallUi = InstallUi(),
    val social: SocialUi = SocialUi(),
    val userStacks: UserStacksUi? = null,
    val stackedBy: StackedByUi = StackedByUi(),
    /** The NIP-C1 proof behind [AppRecord.proofPubkey], when one is stored. */
    val proof: CertificateProof? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModel(
    private val appContext: ZapstoreApplication,
    private val iolite: Iolite,
    private val calls: Call.Factory,
    installedApps: Flow<List<InstalledApp>>,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val appId: String = requireNotNull(savedStateHandle[Routes.APP_ID_ARG])

    private val app: Flow<AppRecord?> = iolite.observeApp(appId)
    private val installer = appContext.installer
    private val originState = MutableStateFlow<InstallOrigin?>(null)

    /** The trust sheet, and a block found before anything downloads. */
    private val installDialog = MutableStateFlow<InstallPlan?>(null)
    private val blocked = MutableStateFlow<String?>(null)
    private enum class Check { Idle, Running, Failed }

    private class Personal(val ranked: List<String>, val follows: Set<String>, val me: String)

    private class TrustInputs(val catalog: Trust?, val me: String?, val personal: Personal?, val check: Check)

    /** The user's own check, tagged with the publisher it was run for. */
    private val personal = MutableStateFlow<Pair<String, Personal>?>(null)
    private val check = MutableStateFlow(Check.Idle)
    private var checkJob: Job? = null

    /**
     * For a publisher with a C1 identity: a listing the catalog signed has nobody to look up. Read by
     * the first-install sheet and the full report. The default is what the bundle carried.
     */
    private fun trustUi(): Flow<TrustUi> {
        val publisher = app.map { it?.authorPubkey.takeIf { _ -> it?.isCatalogSigned == false } }.distinctUntilChanged()
        return publisher.flatMapLatest { author ->
            if (author == null) return@flatMapLatest flowOf(TrustUi())
            val inputs = combine(
                iolite.observeTrust(author),
                appContext.account.account,
                personal,
                check,
            ) { catalog, account, personal, check ->
                TrustInputs(catalog, account?.pubkey, personal?.takeIf { it.first == author }?.second, check)
            }
            // Kind 0 for whoever is listed, the catalog's followers or the user's own: the same query
            // the activity feed uses, so a name and avatar arrive for them like for any other profile.
            val listed = inputs.map { input ->
                val people = input.personal?.ranked ?: input.catalog?.followers.orEmpty().map { it.pubkey }
                people.toSet()
            }
                .distinctUntilChanged()
                .flatMapLatest(iolite::observeProfiles)
            combine(inputs, listed, zaps, commentProfiles) { input, listed, zaps, zapProfiles ->
                val mine = input.personal
                if (mine == null) {
                    TrustUi(
                        followers = input.catalog?.followers.orEmpty().map { TrustPerson(it.pubkey, it.name) },
                        leaked = input.catalog?.leaked == true,
                        canPersonalize = true,
                        checking = input.check == Check.Running,
                        failed = input.check == Check.Failed,
                        profiles = listed,
                    )
                } else {
                    val senders = zaps?.items.orEmpty().map(ZapRecord::pubkey).distinct()
                    TrustUi(
                        followers = mine.ranked.map { TrustPerson(it) },
                        leaked = input.catalog?.leaked == true,
                        personal = true,
                        zappers = senders.filter { it in mine.follows && it != mine.me }.take(TRUST_FOLLOWERS).map { TrustPerson(it) },
                        youFollow = author in mine.follows,
                        youZapped = mine.me in senders,
                        profiles = listed + zapProfiles,
                    )
                }
            }
        }
    }

    /** The signed-in user's own check: Vertex ranks the publisher's followers from their point of view. */
    fun checkTrustPersonally() {
        val author = uiState.value.app?.authorPubkey ?: return
        val signer = appContext.account.signer() ?: return
        if (check.value == Check.Running) return
        check.value = Check.Running
        checkJob = viewModelScope.launch {
            try {
                val (ranked, follows) = coroutineScope {
                    val ranked = async { iolite.reputation(AppConfig.vertexRelays, signer.publicKey, author, signer, limit = TRUST_FOLLOWERS + 2, timeout = TRUST_TIMEOUT, responder = AppConfig.VERTEX_PUBKEY) }
                    val follows = async { runCatching { iolite.follows(signer.publicKey, AppConfig.indexerRelays, TRUST_TIMEOUT) }.getOrDefault(emptySet()) }
                    ranked.await() to follows.await()
                }
                personal.value = author to Personal(ranked.take(TRUST_FOLLOWERS), follows, signer.publicKey)
                check.value = Check.Idle
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                check.value = Check.Failed
            }
        }
    }

    private fun resetTrust() {
        checkJob?.cancel()
        personal.value = null
        check.value = Check.Idle
    }

    private val proof: Flow<CertificateProof?> = app.map { it?.let(iolite::proof) }.distinctUntilChanged()

    private val author: Flow<ProfileRecord?> = app
        .map { it?.bylinePubkey }
        .distinctUntilChanged()
        .flatMapLatest { pubkey ->
            if (pubkey == null) flowOf(null) else iolite.query(Query.profile(pubkey), AppConfig.profileQuery).map { it.items }
        }
        .onStart { emit(null) }

    private val reach: Flow<SocialReach?> = app.map { it?.let(SocialReach::of) }.distinctUntilChanged()

    private val zaps: Flow<QueryState<List<ZapRecord>>?> = reach.flatMapLatest { reach ->
        if (reach == null) flowOf(null)
        else iolite.query(Query.zaps(reach.anchors, reach.recipients, reach.lists, limit = ZAP_LIMIT), AppConfig.zapQuery)
    }.onStart { emit(null) }

    private val comments: Flow<QueryState<List<CommentRecord>>?> = reach.flatMapLatest { reach ->
        if (reach == null) flowOf(null)
        else iolite.query(Query.comments(reach.anchors, reach.lists, limit = COMMENT_LIMIT), AppConfig.commentQuery)
    }.onStart { emit(null) }

    /** Names and pictures for everyone in the feed: comment authors and zap senders. */
    private val commentProfiles: Flow<Map<String, ProfileRecord>> = combine(comments, zaps) { comments, zaps ->
        comments?.items.orEmpty().map(CommentRecord::pubkey).toSet() + zaps?.items.orEmpty().map(ZapRecord::pubkey)
    }
        .distinctUntilChanged()
        .flatMapLatest { pubkeys ->
            if (pubkeys.isEmpty()) {
                flowOf(emptyMap())
            } else {
                iolite.query(Query.profiles(pubkeys), AppConfig.commentProfileQuery).map { it.items }
            }
        }
        .onStart { emit(emptyMap()) }

    private val installUi: Flow<InstallUi> = combine(
        installer.tasks.map { it[appId] }.distinctUntilChanged(),
        app.map { it?.apkHash }.distinctUntilChanged(),
        installDialog,
        blocked,
        trustUi(),
    ) { task, hash, dialog, blocked, trust ->
        installUi(task?.takeIf { it.busy || it.hash == hash || it.phase == TaskPhase.Failed }, dialog, blocked, trust)
    }

    private val zapProfiles: Flow<Map<String, ProfileRecord>> = combine(app.map { it?.zapRecipient }, zaps) { recipient, zaps ->
        topZappers(zaps?.items.orEmpty()).map(Zapper::pubkey).toSet() + listOfNotNull(recipient)
    }
        .distinctUntilChanged()
        .flatMapLatest { pubkeys ->
            if (pubkeys.isEmpty()) flowOf(emptyMap()) else iolite.query(Query.profiles(pubkeys), AppConfig.commentProfileQuery).map { it.items }
        }
        .onStart { emit(emptyMap()) }

    private val catalog = combine(app, author, combine(zaps, zapProfiles, ::Pair), comments, commentProfiles) { app, author, (zaps, zapProfiles), comments, commentProfiles ->
        val split = splitComments(comments?.items.orEmpty(), app?.let { SocialReach.of(it).anchors }.orEmpty())
        AppDetailUiState(
            app = app,
            loaded = true,
            author = author,
            zaps = ZapSummary(
                count = zaps?.items?.size ?: 0,
                totalSats = zaps?.items?.sumOf(ZapRecord::amountSats) ?: 0,
                loading = zaps?.settled != true,
                error = zaps?.relayError(),
                zappers = topZappers(zaps?.items.orEmpty()),
                zapperCount = zaps?.items.orEmpty().distinctBy(ZapRecord::pubkey).size,
                recipient = app?.zapRecipient?.let { ZapRecipient(it, catalog = app.proofPubkey == null) },
                profiles = zapProfiles,
            ),
            comments = CommentsState(
                threads = threadComments(split.main),
                other = threadComments(split.other),
                zaps = zaps?.items.orEmpty(),
                profiles = commentProfiles,
                // The feed is settled once both of its reads are, as on the web page.
                loading = comments?.settled != true || zaps?.settled != true,
                error = comments.toCommentsError(),
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
        .onStart { emit(StackedByUi()) }

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
                write.copy(stacks = state.items.filter { it.apps.isNotEmpty() }, loading = state.items.isEmpty() && !state.settled)
            }
        }

    val uiState: StateFlow<AppDetailUiState> = combine(
        combine(catalog, stackedBy, proof) { catalog, stackedBy, proof -> catalog.copy(stackedBy = stackedBy, proof = proof) },
        originState,
        installUi,
        socialUi,
        userStacks,
    ) { catalog, origin, install, social, stacks ->
        val record = catalog.app
        val known = origin != null
        val installed = origin?.installed == true
        val newer = record != null && known && (!installed || record.versionCode > origin.versionCode)
        val foreign = newer && installed && planFor(record, origin) == InstallPlan.Blocked(BlockReason.Signer)
        catalog.copy(
            social = social,
            userStacks = stacks,
            install = install.copy(
                offer = newer && !foreign,
                updating = installed && newer && !foreign,
                installed = installed && (!newer || foreign),
                signedDifferently = foreign,
                otherStore = origin?.let { storeLabel(otherStore(it.installingPackage, it.updateOwner, appContext.packageName)) },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppDetailUiState())

    init {
        viewModelScope.launch {
            combine(app, installedApps) { record, _ -> record }
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
                iolite.publish(Kinds.Comment, Social.commentTags(anchor, parent, uiState.value.app?.version), content, signer, relays = AppConfig.commentWriteRelays)
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

    /** A NIP-56 report on the app, sent only to the relays of the catalog that supplied it. */
    fun report(type: String, note: String) {
        val app = uiState.value.app ?: return
        val anchor = app.catalogAnchor ?: return
        if (social.value.posting) return
        social.update { it.copy(posting = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val signer = appContext.account.signer() ?: error(appContext.getString(R.string.sign_in_first))
                val relays = withContext(Dispatchers.IO) { iolite.catalogs().firstOrNull { it.id == app.catalogId }?.relays }
                    ?.toSet()?.takeIf { it.isNotEmpty() } ?: setOf(AppConfig.catalogRelay)
                iolite.publish(Kinds.Report, Social.reportTags(anchor, type), note.trim(), signer, relays = relays)
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
     * Zaps [sats] to the app's C1 owner, or else to the catalog that lists it. Signed in or not: without
     * an account this signs the zap request with a throwaway key, the same anonymous zap every Nostr
     * client supports. With a connected wallet it pays right away; otherwise the invoice comes back for
     * the person to pay by hand, instead of blocking the zap on having a wallet connected at all.
     */
    fun zap(sats: Long, note: String) {
        val app = uiState.value.app ?: return
        val anchor = app.catalogAnchor ?: return
        val owner = app.zapRecipient ?: return
        if (sats <= 0 || social.value.zapping) return
        social.update { it.copy(zapping = true, zapError = null, zapped = null, invoice = null) }
        viewModelScope.launch {
            val result = runCatching {
                val signer = appContext.account.signer() ?: LocalSigner(Crypto.randomBytes(32))
                val address = withTimeoutOrNull(PROFILE_WAIT) {
                    iolite.query(Query.profile(owner), AppConfig.profileQuery).first { it.items?.lud16 != null || it.settled }.items?.lud16
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
                if (Bolt11.amountMsat(invoice) != msat) throw ZapException(ZapFailure.Invoice)
                val connection = appContext.wallet.connection.value
                if (connection != null) iolite.walletRequest(connection, "pay_invoice", JSONObject().put("invoice", invoice))
                (connection != null) to invoice
            }
            result.exceptionOrNull()?.let { AppLog.w(TAG, "zap failed: ${zapFailure(it)}") }
            social.update { state ->
                result.fold(
                    onSuccess = { (paid, invoice) ->
                        if (paid) state.copy(zapping = false, zapped = sats) else state.copy(zapping = false, invoice = invoice)
                    },
                    onFailure = { state.copy(zapping = false, zapError = zapMessage(it)) },
                )
            }
        }
    }

    fun clearZap() = social.update { it.copy(zapError = null, zapped = null, invoice = null) }

    /** Opens the signer app. Stays on this screen; nothing here sends the person to Settings. */
    fun signIn() {
        if (social.value.signingIn) return
        social.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch {
            val failure = runCatching { appContext.account.signIn() }.exceptionOrNull()
            failure?.let { AppLog.w(TAG, "sign in failed: ${it::class.simpleName} ${it.message}") }
            social.update { it.copy(signingIn = false, signInError = failure?.message) }
        }
    }

    /** False when [input] is not a NWC connection string. */
    fun connectWallet(input: String): Boolean = appContext.wallet.connect(input)

    /** What failed, for the log: never the address, invoice, or wallet string. */
    private fun zapFailure(error: Throwable): String = when (error) {
        is ZapException -> error.failure.name
        is NwcException -> "wallet ${error.code}"
        else -> error::class.simpleName ?: "unknown"
    }

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

    /** Install or Update: the trust sheet for a first install or a takeover, otherwise straight on. */
    fun prepareInstall() {
        val record = uiState.value.app ?: return
        val origin = originState.value ?: return
        if (uiState.value.install.busy) return
        val plan = planFor(record, origin)
        if (plan is InstallPlan.Blocked) {
            blocked.value = blockMessage(plan.reason)
            return
        }
        val declined = installer.tasks.value[appId]?.let { it.phase == TaskPhase.Ready && it.hash == record.apkHash } == true
        if (plan is InstallPlan.Silent || declined) {
            startInstall()
        } else {
            resetTrust()
            installDialog.value = plan
        }
    }

    fun dismissInstall() {
        resetTrust()
        installDialog.value = null
    }

    fun confirmInstall() {
        if (uiState.value.app == null || installDialog.value == null || uiState.value.install.busy) return
        resetTrust()
        installDialog.value = null
        startInstall()
    }

    /** Starts or resumes, or shows Android's waiting dialog again. A paused download continues from its partial file. */
    fun startInstall() {
        val record = uiState.value.app ?: return
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            blocked.value = appContext.getString(R.string.install_permission)
            return
        }
        blocked.value = null
        installer.install(record)
    }

    /** Stops the download and keeps what arrived. */
    fun pauseInstall() = installer.pause(appId)

    /** Ends the install at any stage and deletes what it downloaded. */
    fun cancelInstall() = installer.cancel(appId)

    /** The error was shown; back to the normal button. */
    fun dismissError() {
        blocked.value = null
        installer.dismiss(appId)
    }

    private fun planFor(record: AppRecord, origin: InstallOrigin) = installPlan(
        deviceSdk = Build.VERSION.SDK_INT,
        ourPackage = appContext.packageName,
        packageId = record.appId,
        origin = origin,
        listedVersionCode = record.versionCode,
        listedHash = record.apkHash,
        listedCertificate = record.certificateHash,
        listedChain = record.certificateChain(),
    )

    private fun blockMessage(reason: BlockReason): String = appContext.getString(reason.message)

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
                    app.catalogSync.installedApps,
                    createSavedStateHandle(),
                )
            }
        }
    }
}

/**
 * Where an app's comments and zaps are read, as `specs/catalog.md` defines them. [anchors] are the
 * coordinates of the known pubkeys: the catalog key, the listing signer, the C1 owner, and the earlier Zapstore key. [recipients] are the
 * pubkeys a zap is paid to, which the receipts are read by when they carry no `a` tag. [lists] are the
 * pubkeys whose NIP-65 inboxes are read as well: the catalog key, and the C1 owner or else the signer.
 */
internal data class SocialReach(
    val anchors: List<AppCoordinate>,
    val recipients: List<String>,
    val lists: List<String>,
) {
    companion object {
        fun of(app: AppRecord) = SocialReach(
            anchors = (app.anchors + listOfNotNull(app.proofPubkey).map { AppCoordinate(it, app.appId) } + AppCoordinate(AppConfig.LEGACY_CATALOG_PUBKEY, app.appId)).distinct(),
            recipients = (app.zapRecipients + AppConfig.LEGACY_CATALOG_PUBKEY).distinct(),
            lists = app.socialLists,
        )
    }
}

/** Lowercase letters and digits joined by `-`, at most 48 characters. */
internal fun stackIdentifier(name: String): String =
    name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("-").take(48).trimEnd('-')
        .ifEmpty { "stack" }

private fun topZappers(zaps: List<ZapRecord>): List<Zapper> =
    zaps.groupBy(ZapRecord::pubkey)
        .map { (pubkey, list) -> Zapper(pubkey, list.sumOf(ZapRecord::amountSats)) }
        .sortedByDescending(Zapper::sats)
        .take(TOP_ZAPPERS)

private fun installUi(task: InstallTask?, dialog: InstallPlan?, blocked: String?, trust: TrustUi): InstallUi = InstallUi(
    trust = trust,
    dialog = dialog,
    phase = when {
        blocked != null -> InstallPhase.Failed
        task == null -> InstallPhase.Idle
        else -> when (task.phase) {
            TaskPhase.Queued -> InstallPhase.Queued
            TaskPhase.Downloading -> InstallPhase.Downloading
            TaskPhase.Paused -> InstallPhase.Paused
            TaskPhase.Verifying -> InstallPhase.Verifying
            TaskPhase.Installing -> InstallPhase.Installing
            TaskPhase.Confirm -> InstallPhase.Confirm
            TaskPhase.Ready -> InstallPhase.Idle
            TaskPhase.Failed -> InstallPhase.Failed
        }
    },
    received = task?.received ?: 0,
    total = task?.total,
    silent = task?.silent ?: true,
    error = blocked ?: task?.error,
)
