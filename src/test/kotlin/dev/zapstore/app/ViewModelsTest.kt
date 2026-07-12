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
) : CatalogRepository {
    override fun query(
        filter: Filter,
        type: QueryType,
        cachedFor: Duration?,
    ): Flow<QueryState> = when (type) {
        QueryType.Local,
        QueryType.LocalAndRemote,
        -> localQuery(filter)
        QueryType.Remote -> remoteQuery(filter)
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
    kind: Int,
    tags: Array<Array<String>> = emptyArray(),
): Event = Event(
    id = id,
    pubKey = "1".repeat(64),
    createdAt = 1_750_000_000,
    kind = kind,
    tags = tags,
    content = "",
    sig = "2".repeat(128),
)
