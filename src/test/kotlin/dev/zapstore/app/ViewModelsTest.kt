package dev.zapstore.app

import androidx.lifecycle.SavedStateHandle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import dev.zapstore.purplequartz.QueryOptions
import dev.zapstore.purplequartz.QueryState
import dev.zapstore.purplequartz.QueryPhase
import dev.zapstore.purplequartz.RemoteMode
import dev.zapstore.purplequartz.SourceMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `catalog defaults to brief one-shot freshness and profiles use one day`() {
        val options = catalogQueryOptions()
        assertEquals(SourceMode.LocalAndRemote, options.sourceMode)
        assertTrue(options.remoteMode is RemoteMode.OneShot)
        assertEquals(30.seconds, options.cachedFor)
        assertEquals(1.days, PROFILE_CACHE_DURATION)

        val forced = catalogQueryOptions(cachedFor = null)
        assertEquals(null, forced.cachedFor)
    }

    @Test
    fun `C1 author is verified only when current proof matches an asset`() {
        val pubkey = "1".repeat(64)
        val hash = "a".repeat(64)
        val asset = event(
            pubKey = pubkey,
            kind = Catalog.assetKind,
            tags = arrayOf(arrayOf("apk_certificate_hash", hash)),
        )
        val proof = event(
            pubKey = pubkey,
            kind = Catalog.c1Kind,
            tags = arrayOf(
                arrayOf("d", hash),
                arrayOf("signature", "signature"),
                arrayOf("expiry", "1800000000"),
            ),
        )

        assertEquals(setOf(pubkey), verifiedAppAuthors(listOf(asset), listOf(proof), now = 1750000000))
        assertTrue(verifiedAppAuthors(listOf(asset), emptyList(), now = 1750000000).isEmpty())
    }

    @Test
    fun `newer revoked C1 replaces older active proof`() {
        val pubkey = "1".repeat(64)
        val hash = "a".repeat(64)
        val asset = event(
            pubKey = pubkey,
            kind = Catalog.assetKind,
            tags = arrayOf(arrayOf("apk_certificate_hash", hash)),
        )
        val oldProof = event(
            id = "f".repeat(64),
            pubKey = pubkey,
            kind = Catalog.c1Kind,
            createdAt = 1_750_000_000,
            tags = arrayOf(
                arrayOf("d", hash),
                arrayOf("signature", "signature"),
                arrayOf("expiry", "1800000000"),
            ),
        )
        val revokedProof = event(
            id = "e".repeat(64),
            pubKey = pubkey,
            createdAt = 1_750_000_001,
            kind = Catalog.c1Kind,
            tags = arrayOf(arrayOf("d", hash), arrayOf("revoked", "retired")),
        )

        assertTrue(verifiedAppAuthors(listOf(asset), listOf(oldProof, revokedProof), now = 1750000000).isEmpty())
    }

    @Test
    fun `home search reads local results and cancels the previous query`() = runTest {
        val localFlows = mutableListOf<MutableSharedFlow<QueryState>>()
        val repository = FakeCatalogRepository(
            localQuery = { filter ->
                if (filter.search != null) {
                    MutableSharedFlow<QueryState>().also(localFlows::add)
                } else {
                    emptyFlow()
                }
            },
        )
        val viewModel = HomeViewModel(repository, SavedStateHandle())
        runCurrent()

        viewModel.onSearchQueryChanged("first")
        viewModel.submitSearch()
        runCurrent()
        assertEquals(1, localFlows.single().subscriptionCount.value)

        viewModel.onSearchQueryChanged("second")
        viewModel.submitSearch()
        runCurrent()
        assertEquals(0, localFlows.first().subscriptionCount.value)
        assertEquals(1, localFlows.last().subscriptionCount.value)

        localFlows.last().emit(
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
    fun `app detail zap receipts resolve the app author outbox`() = runTest {
        var outboxAuthors: List<String>? = null
        var outboxRelays: Set<String>? = null
        val appFlow = MutableSharedFlow<QueryState>()
        val repository = FakeCatalogRepository(
            localQuery = { filter ->
                if (filter.kinds == listOf(Catalog.appKind)) appFlow else emptyFlow()
            },
            outboxQuery = { _, authors, relays ->
                outboxAuthors = authors
                outboxRelays = relays
                emptyFlow()
            },
        )
        AppDetailViewModel(
            repository,
            SavedStateHandle(mapOf(APP_IDENTIFIER_ARGUMENT to "dev.example.app")),
        )
        runCurrent()
        appFlow.emit(queryState(event(kind = Catalog.appKind, pubKey = "7".repeat(64))))
        runCurrent()

        assertEquals(listOf("7".repeat(64)), outboxAuthors)
        assertEquals(Catalog.defaultZapRelays, outboxRelays)
    }

    @Test
    fun `app detail zap and asset queries are bounded`() = runTest {
        val filters = mutableListOf<Filter>()
        val appFlow = MutableSharedFlow<QueryState>()
        val repository = FakeCatalogRepository(
            localQuery = { filter ->
                filters += filter
                if (filter.kinds == listOf(Catalog.appKind)) appFlow else emptyFlow()
            },
        )
        AppDetailViewModel(
            repository,
            SavedStateHandle(mapOf(APP_IDENTIFIER_ARGUMENT to "dev.example.app")),
        )
        runCurrent()

        appFlow.emit(
            queryState(
                event(
                    kind = Catalog.appKind,
                    tags = arrayOf(arrayOf("d", "dev.example.app")),
                ),
            ),
        )
        runCurrent()

        val zapFilters = filters.filter { it.kinds == listOf(Catalog.zapReceiptKind) }
        val assetFilters = filters.filter { it.kinds == listOf(Catalog.assetKind) }
        assertTrue(zapFilters.isNotEmpty())
        assertTrue(assetFilters.isNotEmpty())
        assertTrue(zapFilters.all { it.limit != null })
        assertTrue(assetFilters.all { it.limit != null })
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
    private val outboxQuery: ((List<Filter>, List<String>, Set<String>) -> Flow<QueryState>)? = null,
) : CatalogRepository {
    override suspend fun verifiedApps(apps: List<AppInfo>): List<AppInfo> = apps

    override fun query(
        filters: List<Filter>,
        options: QueryOptions,
    ): Flow<QueryState> = when (options.sourceMode) {
        SourceMode.Local,
        SourceMode.LocalAndRemote,
        -> localQueries?.invoke(filters) ?: filters.singleOrNull()?.let(localQuery) ?: emptyFlow()
        SourceMode.Remote -> remoteQueries?.invoke(filters) ?: filters.singleOrNull()?.let(remoteQuery) ?: emptyFlow()
    }

    override fun queryWithOutbox(
        filters: List<Filter>,
        authors: List<String>,
        cachedFor: Duration?,
        relays: Set<String>,
        remoteMode: RemoteMode,
        unionWithFallback: Boolean,
    ): Flow<QueryState> = outboxQuery?.invoke(filters, authors, relays)
        ?: super.queryWithOutbox(filters, authors, cachedFor, relays, remoteMode, unionWithFallback)

    override fun refreshConnections() = Unit
}

private fun queryState(vararg events: Event): QueryState =
    QueryState(
        items = events.toList(),
        phase = QueryPhase.Complete,
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
