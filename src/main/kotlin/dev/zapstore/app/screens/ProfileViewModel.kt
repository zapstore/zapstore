package dev.zapstore.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppConfig
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn

data class ProfileUiState(
    val pubkey: String,
    val profile: ProfileRecord? = null,
    val profileLoading: Boolean = true,
    val apps: AppListState = AppListState(),
    val stacks: List<StackRecord> = emptyList(),
    val stackApps: Map<String, AppRecord> = emptyMap(),
    val error: String? = null,
    /** What this person commented and zapped on catalog apps, newest first. */
    val activity: List<ActivityItem> = emptyList(),
    val activityLoading: Boolean = true,
    val activityCanLoadMore: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    iolite: Iolite,
    private val pubkey: String,
) : ViewModel() {

    private val profile = iolite.query(Query.profile(pubkey), AppConfig.profileQuery)
    private val apps = iolite.observeApps(AppFilter(author = pubkey))
    private val stacks = iolite.query(Query.stacks(pubkey), AppConfig.authorStacksQuery)
        .map { state -> state.items.filter { it.apps.isNotEmpty() } }
    private val stackApps = stacks.map { it.flatMap(StackRecord::appIds).toSet() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeAppsById)

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
        }
    }

    fun loadMoreActivity() {
        if (uiState.value.activityCanLoadMore) activityLimit.update { it + ACTIVITY_PAGE }
    }

    val uiState: StateFlow<ProfileUiState> = combine(profile, apps, stacks, stackApps, activity) { profileState, apps, stacks, stackApps, activity ->
        ProfileUiState(
            pubkey = pubkey,
            profile = profileState.items,
            profileLoading = profileState.items == null && !profileState.settled,
            apps = AppListState(apps = apps, loading = false),
            stacks = stacks.filter { stack -> stack.appIds.any(stackApps::containsKey) },
            stackApps = stackApps,
            error = profileState.relayError(),
            activity = activity.state.items,
            activityLoading = activity.state.items.isEmpty() && !activity.state.settled,
            activityCanLoadMore = activity.canLoadMore,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProfileUiState(pubkey))

    companion object {
        private const val ACTIVITY_PAGE = 20

        fun factory(iolite: Iolite): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(iolite, requireNotNull(createSavedStateHandle()[Routes.PUBKEY_ARG])) }
        }

        /** The signed-in account's own profile, for the Profile tab. */
        fun factory(iolite: Iolite, pubkey: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(iolite, pubkey) }
        }
    }
}
