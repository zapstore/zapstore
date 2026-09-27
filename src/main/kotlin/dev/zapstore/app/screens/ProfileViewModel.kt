package dev.zapstore.app.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppConfig
import dev.zapstore.app.components.AppListState
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ProfileUiState(
    val pubkey: String,
    val profile: ProfileRecord? = null,
    val profileLoading: Boolean = true,
    val apps: AppListState = AppListState(),
    val error: String? = null,
)

class ProfileViewModel(
    iolite: Iolite,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val pubkey: String = requireNotNull(savedStateHandle[Routes.PUBKEY_ARG])

    private val profile = iolite.query(Query.profile(pubkey), AppConfig.profileQuery)
    private val apps = iolite.observeApps(AppFilter(author = pubkey))

    val uiState: StateFlow<ProfileUiState> = combine(profile, apps) { profileState, apps ->
        ProfileUiState(
            pubkey = pubkey,
            profile = profileState.items,
            profileLoading = profileState.items == null && profileState.isLoading,
            apps = AppListState(apps = apps, loading = false),
            error = profileState.error?.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProfileUiState(pubkey))

    companion object {
        fun factory(iolite: Iolite): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(iolite, createSavedStateHandle()) }
        }
    }
}
