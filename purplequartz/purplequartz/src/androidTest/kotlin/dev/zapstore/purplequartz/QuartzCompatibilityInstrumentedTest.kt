package dev.zapstore.purplequartz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.store.ObservableEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class QuartzCompatibilityInstrumentedTest {
    @Test
    fun publishedStoreEmitsAfterCommitAndSurvivesReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "quartz-compat-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(databaseName).absolutePath
        val event = knownValidEvent()
        context.deleteDatabase(databaseName)

        val observable = ObservableEventStore(EventStore(dbName = path, relay = null))
        try {
            val change = async(start = CoroutineStart.UNDISPATCHED) {
                observable.changes.first()
            }
            observable.insert(event)

            assertTrue(change.await() is ObservableEventStore.StoreChange.Insert)
            assertEquals(listOf(event.id), observable.query<Event>(Filter(ids = listOf(event.id))).map(Event::id))
            runCatching { observable.insert(event) }
            assertEquals(1, observable.count(Filter(ids = listOf(event.id))))
        } finally {
            observable.close()
        }

        val reopened = EventStore(dbName = path, relay = null)
        try {
            assertEquals(listOf(event.id), reopened.query<Event>(Filter(ids = listOf(event.id))).map(Event::id))
            assertEquals(listOf(event.id), reopened.query<Event>(Filter(search = "test")).map(Event::id))
        } finally {
            reopened.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun api29ConsumerConstructsQueriesAllSourcesAndCloses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "purplequartz-consumer-${UUID.randomUUID()}.db"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val okHttpClient = OkHttpClient()
        val relay = "ws://127.0.0.1:1".normalizeRelayUrl()
        val purpleQuartz = PurpleQuartz.create(
            context = context,
            websocketBuilder = BasicOkHttpWebSocket.Builder { okHttpClient },
            parentScope = scope,
            config = PurpleQuartzConfig(databaseName = databaseName),
        )

        try {
            assertEquals(QuerySync.LocalOnly, purpleQuartz.query(Filter(kinds = listOf(1))).first().sync)
            assertEquals(
                QuerySync.Connecting,
                purpleQuartz.query(
                    Filter(kinds = listOf(1)),
                    QuerySource.LocalAndRemote(setOf(relay)),
                ).take(1).toList().single().sync,
            )
            assertEquals(
                QuerySync.Connecting,
                purpleQuartz.query(
                    Filter(kinds = listOf(1)),
                    QuerySource.Remote(setOf(relay)),
                ).take(1).toList().single().sync,
            )
        } finally {
            purpleQuartz.close()
            scope.cancel()
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
            context.deleteDatabase(databaseName)
        }
    }

    private fun knownValidEvent(): Event {
        val event = NostrSignerSync(KeyPair()).sign<Event>(
            EventTemplate(
                createdAt = 1_753_988_264,
                kind = 1,
                tags = emptyArray(),
                content = "test",
            ),
        )
        assertTrue(event.verifyId())
        assertTrue(event.verifySignature())
        return event
    }
}
