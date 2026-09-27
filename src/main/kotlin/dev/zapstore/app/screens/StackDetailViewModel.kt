package dev.zapstore.app.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.components.AppListState
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.StackRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StackDetailUiState(
    val stack: StackRecord? = null,
    val stackLoading: Boolean = true,
    /** Listings in stack order; apps not in the local catalog are omitted. */
    val apps: AppListState = AppListState(),
    val appsById: Map<String, AppRecord> = emptyMap(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StackDetailViewModel(
    private val iolite: Iolite,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val author: String = requireNotNull(savedStateHandle[Routes.STACK_AUTHOR_ARG])
    private val identifier: String = requireNotNull(savedStateHandle[Routes.STACK_ID_ARG])

    private val stack = iolite.query(Query.stack(author, identifier))

    private val appsById: Flow<Map<String, AppRecord>> = stack
        .map { it.items?.appIds.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeAppsById)

    private val profiles = appsById.map { it.values.toList() }.authorProfiles(iolite)

    val uiState: StateFlow<StackDetailUiState> = combine(stack, appsById, profiles) { stackState, appsById, profiles ->
        val stack = stackState.items
        StackDetailUiState(
            stack = stack,
            stackLoading = stack == null && stackState.isLoading,
            apps = AppListState(
                apps = stack?.appIds.orEmpty().mapNotNull(appsById::get),
                profiles = profiles,
                loading = false,
            ),
            appsById = appsById,
            error = stackState.error?.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StackDetailUiState())

    companion object {
        fun factory(iolite: Iolite): ViewModelProvider.Factory = viewModelFactory {
            initializer { StackDetailViewModel(iolite, createSavedStateHandle()) }
        }
    }
}
