package dev.zapstore.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.StackRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StacksUiState(
    val stacks: List<StackRecord> = emptyList(),
    val apps: Map<String, AppRecord> = emptyMap(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
    /** `updated_at` of the oldest stack fetched, shown or not; the next page starts below it. */
    val oldest: Long? = null,
)

/**
 * Everyone's public stacks that name at least one catalog app: the first page streams from the stack relays while the page is open;
 * each older page is a one-shot fetch that lands in SQLite, and the list re-reads with a larger limit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StacksViewModel(private val iolite: Iolite) : ViewModel() {
    private val limit = MutableStateFlow(PAGE_SIZE)
    private val loadingMore = MutableStateFlow(false)

    private val stacks = limit.flatMapLatest { limit ->
        iolite.query(Query.socialStacks(AppConfig.platforms, limit), AppConfig.socialStacksQuery)
            .map { state -> state to limit }
    }

    private val apps = stacks.map { (state, _) -> state.items.flatMap(StackRecord::appIds).toSet() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeAppsById)

    private val profiles = stacks.map { (state, _) -> state.items.map(StackRecord::pubkey).toSet() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeProfiles)

    val uiState: StateFlow<StacksUiState> = combine(stacks, apps, profiles, loadingMore) { (state, limit), apps, profiles, more ->
        StacksUiState(
            stacks = state.items.filter { stack -> stack.appIds.any(apps::containsKey) },
            apps = apps,
            profiles = profiles,
            loading = state.items.isEmpty() && state.isLoading,
            loadingMore = more,
            canLoadMore = state.items.size >= limit,
            error = state.error?.message,
            oldest = state.items.lastOrNull()?.updatedAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StacksUiState())

    fun loadMore() {
        if (loadingMore.value) return
        val oldest = uiState.value.oldest ?: return
        loadingMore.value = true
        viewModelScope.launch {
            try {
                val older = Query.socialStacks(AppConfig.platforms, PAGE_SIZE, until = oldest - 1)
                iolite.query(older, AppConfig.olderStacksQuery).first { it.isTerminal }
                limit.value += PAGE_SIZE
            } finally {
                loadingMore.value = false
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20

        fun factory(iolite: Iolite): ViewModelProvider.Factory = viewModelFactory {
            initializer { StacksViewModel(iolite) }
        }
    }
}
