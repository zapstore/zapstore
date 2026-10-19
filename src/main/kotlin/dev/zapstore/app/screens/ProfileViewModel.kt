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
import dev.zapstore.iolite.StackRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ProfileUiState(
    val pubkey: String,
    val profile: ProfileRecord? = null,
    val profileLoading: Boolean = true,
    val apps: AppListState = AppListState(),
    val stacks: List<StackRecord> = emptyList(),
    val stackApps: Map<String, AppRecord> = emptyMap(),
    val error: String? = null,
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

    val uiState: StateFlow<ProfileUiState> = combine(profile, apps, stacks, stackApps) { profileState, apps, stacks, stackApps ->
        ProfileUiState(
            pubkey = pubkey,
            profile = profileState.items,
            profileLoading = profileState.items == null && profileState.isLoading,
            apps = AppListState(apps = apps, loading = false),
            stacks = stacks.filter { stack -> stack.appIds.any(stackApps::containsKey) },
            stackApps = stackApps,
            error = profileState.error?.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProfileUiState(pubkey))

    companion object {
        fun factory(iolite: Iolite): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(iolite, requireNotNull(createSavedStateHandle()[Routes.PUBKEY_ARG])) }
        }

        /** The signed-in account's own profile, for the Profile tab. */
        fun factory(iolite: Iolite, pubkey: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(iolite, pubkey) }
        }
    }
}
