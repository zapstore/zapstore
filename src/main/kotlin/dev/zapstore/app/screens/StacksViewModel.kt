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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
 * The catalog's own stacks come after everyone else's, so they sit at the bottom of the first load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StacksViewModel(private val iolite: Iolite) : ViewModel() {
    private val limit = MutableStateFlow(PAGE_SIZE)
    /** Older pages still inside their one-shot window. */
    private val loadingMore = MutableStateFlow(0)
    private val requestedPages = mutableSetOf<Long>()

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

    /** The keys that sign the active catalogs' own stacks, and the key those stacks were signed with before the rotation. */
    private val catalogKeys: Flow<Set<String>> = iolite.observeCatalogs()
        .map { catalogs -> catalogs.mapNotNull { it.manifestPubkey }.toSet() + AppConfig.LEGACY_CATALOG_PUBKEY }
        .distinctUntilChanged()

    val uiState: StateFlow<StacksUiState> = combine(stacks, apps, profiles, loadingMore, catalogKeys) { (state, limit), apps, profiles, more, catalogKeys ->
        StacksUiState(
            // People's stacks first. The catalog's own are the same on every device, so they go last. Each group keeps its order.
            stacks = state.items
                .filter { stack -> stack.appIds.any(apps::containsKey) }
                .partition { stack -> stack.pubkey !in catalogKeys }
                .let { (people, catalog) -> people + catalog },
            apps = apps,
            profiles = profiles,
            loading = state.items.isEmpty() && !state.settled,
            loadingMore = more > 0,
            canLoadMore = state.items.size >= limit,
            error = state.relayError(),
            oldest = state.items.lastOrNull()?.updatedAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StacksUiState())

    /** Shows one more page now; the older stacks are fetched alongside and appear as they land. */
    fun loadMore() {
        val oldest = uiState.value.oldest ?: return
        if (!requestedPages.add(oldest)) return
        limit.value += PAGE_SIZE
        loadingMore.update { it + 1 }
        viewModelScope.launch {
            try {
                val older = Query.socialStacks(AppConfig.platforms, PAGE_SIZE, until = oldest - 1)
                iolite.query(older, AppConfig.olderStacksQuery).first { it.settled }
            } finally {
                loadingMore.update { it - 1 }
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
