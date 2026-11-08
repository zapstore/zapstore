package dev.zapstore.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppConfig
import dev.zapstore.app.ZapstoreApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import dev.zapstore.app.ZapMarkdown
import dev.zapstore.app.components.resolvedName
import dev.zapstore.app.components.AppListState
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.StackRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn

data class ProfileUiState(
    val pubkey: String,
    val profile: ProfileRecord? = null,
    val apps: AppListState = AppListState(),
    val stacks: List<StackRecord> = emptyList(),
    val stackApps: Map<String, AppRecord> = emptyMap(),
    val error: String? = null,
    /** What this person commented and zapped on catalog apps, newest first. */
    val activity: List<ActivityItem> = emptyList(),
    val activityLoading: Boolean = true,
    val activityCanLoadMore: Boolean = false,
    /** Names of the people the bio mentions by npub, by hex pubkey, so the bio reads "@name". */
    val mentions: Map<String, String> = emptyMap(),
    /** Who vouches for this person: the catalog's followers, or the signed-in user's own after they ask. */
    val trust: TrustUi = TrustUi(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    private val appContext: ZapstoreApplication,
    private val iolite: Iolite,
    private val pubkey: String,
) : ViewModel() {
    private enum class Check { Idle, Running, Failed }

    private class Personal(val ranked: List<String>, val follows: Set<String>, val me: String)

    private val personal = MutableStateFlow<Personal?>(null)
    private val check = MutableStateFlow(Check.Idle)
    private var checkJob: Job? = null

    /**
     * The same standing the app page shows for its developer: the catalog's followers first, then the user's
     * own view once they ask for it. Zaps are about an app, so there are none here.
     */
    private val trust: Flow<TrustUi> = combine(iolite.observeTrust(pubkey), appContext.account.account, personal, check) { catalog, _, personal, check ->
        Triple(catalog, personal, check)
    }.flatMapLatest { (catalog, mine, check) ->
        val listed = (mine?.ranked ?: catalog?.followers.orEmpty().map { it.pubkey }).toSet()
        iolite.observeProfiles(listed).map { profiles ->
            if (mine == null) {
                TrustUi(
                    followers = catalog?.followers.orEmpty().map { TrustPerson(it.pubkey, it.name) },
                    leaked = catalog?.leaked == true,
                    canPersonalize = true,
                    checking = check == Check.Running,
                    failed = check == Check.Failed,
                    profiles = profiles,
                )
            } else {
                TrustUi(
                    followers = mine.ranked.map { TrustPerson(it) },
                    leaked = catalog?.leaked == true,
                    personal = true,
                    youFollow = pubkey in mine.follows,
                    profiles = profiles,
                )
            }
        }
    }.onStart { emit(TrustUi()) }

    /** The signed-in user's own check: Vertex's ranking of this person's followers from their point of view, else the people they follow who follow them. */
    fun checkTrustPersonally() {
        val signer = appContext.account.signer() ?: return
        if (check.value == Check.Running) return
        check.value = Check.Running
        checkJob = viewModelScope.launch {
            try {
                // Vertex ranks them from your point of view, but bills the key that asks, and a reader's key often has
                // no credits. It answers with an error within seconds then, so the people you follow who follow them,
                // from their kind 3 lists, stand in.
                val (ranked, follows) = coroutineScope {
                    val vertex = async {
                        try {
                            iolite.reputation(AppConfig.vertexRelays, signer.publicKey, pubkey, signer, TRUST_PEOPLE, VERTEX_TIMEOUT, AppConfig.VERTEX_PUBKEY)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            null
                        }
                    }
                    val mine = async { iolite.follows(signer.publicKey, AppConfig.indexerRelays, TRUST_TIMEOUT) }
                    val follows = mine.await()
                    (vertex.await() ?: iolite.followersAmong(pubkey, follows, AppConfig.followerRelays, TRUST_PEOPLE, TRUST_TIMEOUT)) to follows
                }
                personal.value = Personal(ranked.take(TRUST_PEOPLE), follows, signer.publicKey)
                check.value = Check.Idle
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                check.value = Check.Failed
            }
        }
    }

    private val profile = iolite.query(Query.profile(pubkey), AppConfig.profileQuery)
    private val apps = iolite.observeApps(AppFilter(author = pubkey))
    private val stacks = iolite.query(Query.stacks(pubkey), AppConfig.authorStacksQuery)
        .map { state -> state.items.filter { it.apps.isNotEmpty() } }
    private val stackApps = stacks.map { it.flatMap(StackRecord::appIds).toSet() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeAppsById)

    /** Names for the `npub`s in the bio, fetched like any profile; empty until they arrive. */
    private val mentions: Flow<Map<String, String>> = profile
        .map { state -> state.items?.about?.let(ZapMarkdown::mentionedPubkeys).orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeProfiles)
        .map { profiles -> profiles.mapNotNull { (key, record) -> resolvedName(record)?.let { key to it } }.toMap() }
        .onStart { emit(emptyMap()) }

    private val activityLimit = MutableStateFlow(ACTIVITY_PAGE)

    /** The person's comments (from their outbox relays) and zaps, on apps the catalog lists. Older pages are one shot. */
    private val activity: Flow<ActivityPage> = activityLimit.flatMapLatest { limit ->
        iolite.query(Query.authorActivity(pubkey, limit), AppConfig.profileActivityQuery).flatMapLatest { state ->
            val record = state.items
            iolite.observeAppsById(record.appIds).map { apps ->
                ActivityPage(
                    QueryState(record.items(apps).take(limit), state.relays, state.settled),
                    canLoadMore = record.comments.size >= limit || record.zaps.size >= limit,
                )
            }
        }.onStart { emit(ActivityPage(QueryState(emptyList(), settled = false), canLoadMore = false)) }
    }

    fun loadMoreActivity() {
        if (uiState.value.activityCanLoadMore) activityLimit.update { it + ACTIVITY_PAGE }
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        combine(profile, apps, stacks, stackApps, activity) { profileState, apps, stacks, stackApps, activity ->
            ProfileUiState(
                pubkey = pubkey,
                profile = profileState.items,
                apps = AppListState(apps = apps, loading = false),
                stacks = stacks.filter { stack -> stack.appIds.any(stackApps::containsKey) },
                stackApps = stackApps,
                error = profileState.relayError(),
                activity = activity.state.items,
                activityLoading = activity.state.items.isEmpty() && !activity.state.settled,
                activityCanLoadMore = activity.canLoadMore,
            )
        },
        mentions,
        trust,
    ) { state, mentions, trust -> state.copy(mentions = mentions, trust = trust) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProfileUiState(pubkey))

    companion object {
        private const val ACTIVITY_PAGE = 20

        private const val TRUST_PEOPLE = 5
        private val TRUST_TIMEOUT = 15.seconds
        private val VERTEX_TIMEOUT = 8.seconds

        fun factory(app: ZapstoreApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(app, app.iolite, requireNotNull(createSavedStateHandle()[Routes.PUBKEY_ARG])) }
        }

        /** The signed-in account's own profile, for the Profile tab. */
        fun factory(app: ZapstoreApplication, pubkey: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(app, app.iolite, pubkey) }
        }
    }
}
