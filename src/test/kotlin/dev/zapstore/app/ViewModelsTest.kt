package dev.zapstore.app

import androidx.lifecycle.SavedStateHandle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import dev.zapstore.purplequartz.QueryState
import dev.zapstore.purplequartz.QuerySync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `home search maps results and cancels the previous query`() = runTest {
        val remoteFlows = mutableListOf<MutableSharedFlow<QueryState>>()
        val repository = FakeCatalogRepository(
            remoteQuery = {
                MutableSharedFlow<QueryState>().also(remoteFlows::add)
            },
        )
        val viewModel = HomeViewModel(repository, SavedStateHandle())
        runCurrent()

        viewModel.onSearchQueryChanged("first")
        viewModel.submitSearch()
        runCurrent()
        assertEquals(1, remoteFlows.single().subscriptionCount.value)

        viewModel.onSearchQueryChanged("second")
        viewModel.submitSearch()
        runCurrent()
        assertEquals(0, remoteFlows.first().subscriptionCount.value)
        assertEquals(1, remoteFlows.last().subscriptionCount.value)

        remoteFlows.last().emit(
            queryState(
                event(
                    kind = Catalog.appKind,
                    tags = arrayOf(
                        arrayOf("d", "second"),
                        arrayOf("name", "Second App"),
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals("Second App", viewModel.uiState.value.searchResults.single().name)
        assertEquals("1 results for “second”", viewModel.uiState.value.searchMessage)
    }

    @Test
    fun `stack changes cancel the old app query`() = runTest {
        val stackFlow = MutableSharedFlow<QueryState>()
        val appFlows = mutableListOf<MutableSharedFlow<QueryState>>()
        var queryCount = 0
        val repository = FakeCatalogRepository(
            localQuery = {
                if (queryCount++ == 0) {
                    stackFlow
                } else {
                    MutableSharedFlow<QueryState>().also(appFlows::add)
                }
            },
        )
        StackDetailViewModel(
            repository,
            SavedStateHandle(mapOf(STACK_ID_ARGUMENT to "a".repeat(64))),
        )
        runCurrent()

        stackFlow.emit(stackState("first"))
        runCurrent()
        assertEquals(1, appFlows.single().subscriptionCount.value)

        stackFlow.emit(stackState("second"))
        runCurrent()
        assertEquals(0, appFlows.first().subscriptionCount.value)
        assertEquals(1, appFlows.last().subscriptionCount.value)
    }

    @Test
    fun `release feed pages through apps and stops after a short page`() = runTest {
        val appFilters = mutableListOf<Filter>()
        val appFlows = mutableListOf<MutableSharedFlow<QueryState>>()
        val repository = FakeCatalogRepository(
            localQuery = { filter ->
                if (filter.kinds == listOf(Catalog.appKind)) {
                    appFilters += filter
                    MutableSharedFlow<QueryState>().also(appFlows::add)
                } else {
                    emptyFlow()
                }
            },
        )
        val viewModel = HomeViewModel(repository, SavedStateHandle())
        runCurrent()

        appFlows.single().emit(
            queryState(
                *(0 until 20).map { index ->
                    event(
                        id = "app-$index",
                        kind = Catalog.appKind,
                        createdAt = 1_750_000_000L - index,
                        tags = arrayOf(arrayOf("d", "app-$index")),
                    )
                }.toTypedArray(),
                event(
                    id = "not-an-app",
                    kind = Catalog.releaseKind,
                    createdAt = 1_750_000_001L,
                    tags = arrayOf(arrayOf("i", "not-an-app")),
                ),
            ),
        )
        runCurrent()

        assertEquals(20, viewModel.uiState.value.releaseFeed.entries.size)
        assertEquals(true, viewModel.uiState.value.releaseFeed.canLoadMore)

        viewModel.loadMoreReleases()
        runCurrent()

        assertEquals(2, appFilters.size)
        assertEquals(1_749_999_981L, appFilters.last().until)
        assertEquals(21, appFilters.last().limit)

        appFlows.last().emit(
            queryState(
                event(
                    id = "app-last",
                    kind = Catalog.appKind,
                    createdAt = 1_749_999_979L,
                    tags = arrayOf(arrayOf("d", "last-app")),
                ),
            ),
        )
        runCurrent()

        assertEquals(21, viewModel.uiState.value.releaseFeed.entries.size)
        assertEquals(false, viewModel.uiState.value.releaseFeed.canLoadMore)
    }

    @Test
    fun `release feed does not skip apps tied at a page boundary`() = runTest {
        val appFilters = mutableListOf<Filter>()
        val appFlows = mutableListOf<MutableSharedFlow<QueryState>>()
        val repository = FakeCatalogRepository(
            localQuery = { filter ->
                if (filter.kinds == listOf(Catalog.appKind)) {
                    appFilters += filter
                    MutableSharedFlow<QueryState>().also(appFlows::add)
                } else {
                    emptyFlow()
                }
            },
        )
        val viewModel = HomeViewModel(repository, SavedStateHandle())
        runCurrent()

        val timestamp = 1_750_000_000L
        val apps = (0 until 25).map { index ->
            event(
                id = "tied-app-$index",
                kind = Catalog.appKind,
                createdAt = timestamp,
                tags = arrayOf(arrayOf("d", "tied-app-$index")),
            )
        }
        appFlows.single().emit(queryState(*apps.take(20).toTypedArray()))
        runCurrent()

        viewModel.loadMoreReleases()
        runCurrent()

        assertEquals(timestamp, appFilters.last().until)
        assertEquals(40, appFilters.last().limit)

        appFlows.last().emit(queryState(*apps.toTypedArray()))
        runCurrent()

        assertEquals(25, viewModel.uiState.value.releaseFeed.entries.size)
        assertEquals(false, viewModel.uiState.value.releaseFeed.canLoadMore)
    }

    @Test
    fun `release feed outputs apps ordered by latest release or asset`() = runTest {
        val appFlow = MutableSharedFlow<QueryState>()
        val releaseFlow = MutableSharedFlow<QueryState>()
        var releaseFilters = emptyList<Filter>()
        val repository = FakeCatalogRepository(
            localQuery = { filter ->
                if (filter.kinds == listOf(Catalog.appKind)) appFlow else emptyFlow()
            },
            localQueries = { filters ->
                if (filters.size == 1) {
                    if (filters.single().kinds == listOf(Catalog.appKind)) appFlow else emptyFlow()
                } else {
                    releaseFilters = filters
                    releaseFlow
                }
            },
        )
        val viewModel = HomeViewModel(repository, SavedStateHandle())
        runCurrent()

        appFlow.emit(
            queryState(
                event(
                    id = "app-one",
                    kind = Catalog.appKind,
                    createdAt = 100,
                    tags = arrayOf(arrayOf("d", "one")),
                ),
                event(
                    id = "app-two",
                    kind = Catalog.appKind,
                    createdAt = 90,
                    tags = arrayOf(arrayOf("d", "two")),
                ),
                event(
                    id = "app-without-release",
                    kind = Catalog.appKind,
                    createdAt = 500,
                    tags = arrayOf(arrayOf("d", "without-release")),
                ),
            ),
        )
        runCurrent()

        assertEquals(3, releaseFilters.size)
        assertEquals(true, releaseFilters.all { it.kinds == Catalog.releaseKinds })
        assertEquals(true, releaseFilters.all { it.limit == 1 })

        releaseFlow.emit(
            queryState(
                event(
                    id = "release-one",
                    kind = Catalog.releaseKind,
                    createdAt = 200,
                    tags = arrayOf(
                        arrayOf("i", "one"),
                        arrayOf("version", "1.0"),
                    ),
                ),
                event(
                    id = "release-two",
                    kind = Catalog.releaseKind,
                    createdAt = 250,
                    tags = arrayOf(
                        arrayOf("i", "two"),
                        arrayOf("version", "2.0"),
                    ),
                ),
                event(
                    id = "asset-two",
                    kind = Catalog.assetKind,
                    createdAt = 300,
                    tags = arrayOf(
                        arrayOf("i", "two"),
                        arrayOf("version", "2.1"),
                    ),
                ),
                event(
                    id = "not-a-release",
                    kind = Catalog.appStackKind,
                    createdAt = 1_000,
                    tags = arrayOf(arrayOf("i", "one")),
                ),
            ),
        )
        runCurrent()

        val entries = viewModel.uiState.value.releaseFeed.entries
        assertEquals(listOf("two", "one", "without-release"), entries.map { it.app.identifier })
        assertEquals(listOf(Catalog.assetKind, Catalog.releaseKind, null), entries.map { it.release?.event?.kind })
        assertEquals(true, entries.all { it.app.event.kind == Catalog.appKind })
    }

    @Test
    fun `legacy release d tag resolves its app identifier`() {
        val release = ReleaseInfo(
            event(
                kind = Catalog.releaseKind,
                tags = arrayOf(arrayOf("d", "dev.example.app@1.2.3")),
            ),
        )

        assertEquals("dev.example.app", release.appIdentifier)
    }

    @Test
    fun `profile release feed filters apps by profile pubkey`() = runTest {
        val filters = mutableListOf<Filter>()
        val pubkey = "b".repeat(64)
        val repository = FakeCatalogRepository(
            localQuery = { filter ->
                filters += filter
                emptyFlow()
            },
        )

        ProfileViewModel(
            repository,
            SavedStateHandle(mapOf(PROFILE_PUBKEY_ARGUMENT to pubkey)),
        )
        runCurrent()

        val appFilter = filters.single { it.kinds == listOf(Catalog.appKind) }
        assertEquals(listOf(pubkey), appFilter.authors)
    }

    private fun stackState(identifier: String): QueryState = queryState(
        event(
            id = "stack-$identifier",
            kind = Catalog.appStackKind,
            tags = arrayOf(
                arrayOf("name", identifier),
                arrayOf("a", "${Catalog.appKind}:${"1".repeat(64)}:$identifier"),
            ),
        ),
    )
}

private class FakeCatalogRepository(
    private val localQuery: (Filter) -> Flow<QueryState> = { emptyFlow() },
    private val remoteQuery: (Filter) -> Flow<QueryState> = { emptyFlow() },
    private val localQueries: ((List<Filter>) -> Flow<QueryState>)? = null,
    private val remoteQueries: ((List<Filter>) -> Flow<QueryState>)? = null,
) : CatalogRepository {
    override fun query(
        filters: List<Filter>,
        type: QueryType,
        cachedFor: Duration?,
        relays: Set<String>,
    ): Flow<QueryState> = when (type) {
        QueryType.Local,
        QueryType.LocalAndRemote,
        -> localQueries?.invoke(filters) ?: filters.singleOrNull()?.let(localQuery) ?: emptyFlow()
        QueryType.Remote -> remoteQueries?.invoke(filters) ?: filters.singleOrNull()?.let(remoteQuery) ?: emptyFlow()
    }

    override fun refreshConnections() = Unit
}

private fun queryState(vararg events: Event): QueryState =
    QueryState(
        items = events.toList(),
        sync = QuerySync.Complete,
    )

private fun event(
    id: String = "a".repeat(64),
    pubKey: String = "1".repeat(64),
    kind: Int,
    createdAt: Long = 1_750_000_000,
    tags: Array<Array<String>> = emptyArray(),
): Event = Event(
    id = id,
    pubKey = pubKey,
    createdAt = createdAt,
    kind = kind,
    tags = tags,
    content = "",
    sig = "2".repeat(128),
)
