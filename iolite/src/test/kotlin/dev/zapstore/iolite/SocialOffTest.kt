package dev.zapstore.iolite

import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialOffTest {
    private val device = key(1)
    private val author = key(2)
    private val catalog = key(3)
    private val account = key(4)
    private val coordinate = AppCoordinate(catalog.publicKey, "com.example.app")

    private suspend fun stack(signer: LocalSigner, id: String) = signer.sign(
        1_700_000_000,
        Kinds.AppStack,
        listOf(listOf("d", id), listOf("name", id), listOf("a", "32267:${signer.publicKey}:com.example.$id")),
        "",
    )

    private suspend fun socialEvents(): List<Event> = listOf(
        author.sign(1_700_000_000, Kinds.Comment, listOf(listOf("A", coordinate.toString()), listOf("i", "com.example.app")), "nice"),
        author.sign(1_700_000_001, Kinds.Zap, listOf(listOf("i", "com.example.app"), listOf("amount", "21000")), ""),
        author.sign(1_700_000_002, Kinds.RelayList, listOf(listOf("r", "wss://outbox.test")), ""),
        stack(author, "theirs"),
    )

    @Test
    fun ingestDropsSocialEventsWhileOffAndKeepsTheRest() = runBlocking {
        withIolite(config = IoliteConfig(social = false)) { iolite ->
            val profile = author.sign(1_700_000_003, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
            val own = stack(device, "own")
            assertEquals(2, iolite.ingest(socialEvents() + listOf(profile, own)))
            assertEquals("Ada", iolite.query(Query.profile(author.publicKey)).first().items?.name)
            assertEquals(listOf("own"), iolite.query(Query.stacks(device.publicKey)).first().items.map { it.identifier })
            assertTrue(iolite.query(Query.stacks(author.publicKey)).first().items.isEmpty())
            assertTrue(iolite.query(Query.comments(listOf(coordinate))).first().items.isEmpty())

            iolite.setSocial(true)
            assertEquals(4, iolite.ingest(socialEvents()))
        }
    }

    @Test
    fun wipeSocialKeepsCatalogStacksProfilesAndWhatTheDeviceSigned() = runBlocking {
        withIolite { iolite ->
            val profile = author.sign(1_700_000_003, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
            iolite.ingest(socialEvents() + listOf(profile, stack(catalog, "curated"), stack(device, "own")))
            val mine = iolite.publish(1, emptyList(), "device note", device)
            iolite.publish(1, emptyList(), "account note", account)
            assertEquals(2, iolite.pendingPublishes().size)

            iolite.setSocial(false)
            iolite.wipeSocial(listOf(catalog.publicKey))

            assertTrue(iolite.query(Query.comments(listOf(coordinate))).first().items.isEmpty())
            assertTrue(iolite.query(Query.zaps(listOf(coordinate))).first().items.isEmpty())
            assertTrue(iolite.query(Query.stacks(author.publicKey)).first().items.isEmpty())
            assertEquals(listOf("curated"), iolite.query(Query.stacks(catalog.publicKey)).first().items.map { it.identifier })
            assertEquals(listOf("own"), iolite.query(Query.stacks(device.publicKey)).first().items.map { it.identifier })
            assertEquals("Ada", iolite.query(Query.profile(author.publicKey)).first().items?.name)
            assertNull(iolite.store.mailboxes(listOf(author.publicKey))[author.publicKey])
            assertEquals(listOf(mine.id), iolite.pendingPublishes().map { it.id })
        }
    }

    @Test
    fun wipeSocialDropsAProfileRowThatOnlyHeldARelayList() = runBlocking {
        withIolite { iolite ->
            iolite.ingest(listOf(author.sign(1_700_000_002, Kinds.RelayList, listOf(listOf("r", "wss://outbox.test")), "")))
            assertEquals(setOf("wss://outbox.test"), iolite.store.mailboxes(listOf(author.publicKey)).getValue(author.publicKey).outboxes.map { it.url }.toSet())
            iolite.wipeSocial(emptyList())
            assertTrue(iolite.store.mailboxes(listOf(author.publicKey)).isEmpty())
            assertNull(iolite.query(Query.profile(author.publicKey)).first().items)
        }
    }

    @Test
    fun aRoutedReadAsksForRelayListsOnlyWhileOn() = runBlocking {
        val relay = "ws://own.test".normalizeRelayUrl()
        val options = QueryOptions.localAndRemote(setOf(relay), RemoteMode.Stream, useAuthorRelays = true)

        val on = MemoryNetwork()
        withIolite(network = on) { iolite ->
            val job = async { iolite.query(Query.profile(author.publicKey), options).first { it.settled } }
            on.awaitReq(relay.url, Kinds.RelayList)
            job.cancel()
        }

        val off = MemoryNetwork()
        withIolite(network = off, config = IoliteConfig(social = false)) { iolite ->
            val job = async { iolite.query(Query.profile(author.publicKey), options).first { it.settled } }
            off.awaitReq(relay.url, Kinds.Profile)
            delay(100)
            assertFalse(off.relays.values.any { relayLog -> relayLog.sent.any { it.contains("${Kinds.RelayList}") } })
            job.cancel()
        }
    }

    @Test
    fun aLocalOnlyReadStartsNoRelayWorkWhileOffAndResumesWhenSocialReturns() = runBlocking {
        val network = MemoryNetwork()
        val relay = "ws://comments.test".normalizeRelayUrl()
        val options = QueryOptions.localAndRemote(setOf(relay), RemoteMode.Stream, whenSocialOff = WhenSocialOff.LocalOnly)
        withIolite(network = network, config = IoliteConfig(social = false)) { iolite ->
            val job = async { iolite.query(Query.comments(listOf(coordinate)), options).first { false } }
            delay(150)
            assertTrue(network.relays.isEmpty())

            iolite.setSocial(true)
            network.awaitReq(relay.url, Kinds.Comment)

            iolite.setSocial(false)
            network.awaitSent(relay.url) { it.startsWith("""["CLOSE"""") }
            job.cancel()
        }
    }

    @Test
    fun aReadThatStaysOnSomeRelaysAsksOnlyThemWhileOff() = runBlocking {
        val network = MemoryNetwork()
        val general = "ws://general.test".normalizeRelayUrl()
        val catalogRelay = "ws://catalog.test".normalizeRelayUrl()
        val options = QueryOptions.localAndRemote(
            setOf(general),
            RemoteMode.Stream,
            useAuthorRelays = true,
            whenSocialOff = WhenSocialOff.Only(setOf(catalogRelay)),
        )
        withIolite(network = network, config = IoliteConfig(social = false)) { iolite ->
            val job = async { iolite.query(Query.profile(author.publicKey), options).first { false } }
            network.awaitReq(catalogRelay.url, Kinds.Profile)
            delay(100)
            assertNull(network.relays[general.url])
            assertFalse(network.relays.values.any { relayLog -> relayLog.sent.any { it.contains("${Kinds.RelayList}") } })
            job.cancel()
        }
    }

    @Test
    fun aNewProfileIsCopiedToTheMirrorOnce() = runBlocking {
        val network = MemoryNetwork()
        val source = "ws://source.test".normalizeRelayUrl()
        val mirror = "ws://mirror.test".normalizeRelayUrl()
        withIolite(network = network, config = mirrorConfig(mirror)) { iolite ->
            val profile = author.sign(1_700_000_003, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
            readProfile(iolite, network, source, profile)
            val copy = network.awaitSent(mirror.url) { it.startsWith("""["EVENT"""") && it.contains(profile.id) }
            assertEquals(profile.id, JSONArray(copy).getJSONObject(1).getString("id"))

            // The same event again is not newer than what is stored, so it is not sent again.
            readProfile(iolite, network, source, profile)
            delay(100)
            assertEquals(1, network.relays.getValue(mirror.url).sent.count { it.contains(profile.id) })
        }
    }

    @Test
    fun aProfileFromTheMirrorIsNotSentBack() = runBlocking {
        val network = MemoryNetwork()
        val mirror = "ws://mirror.test".normalizeRelayUrl()
        withIolite(network = network, config = mirrorConfig(mirror)) { iolite ->
            val profile = author.sign(1_700_000_003, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
            readProfile(iolite, network, mirror, profile)
            delay(100)
            assertFalse(network.relays.getValue(mirror.url).sent.any { it.startsWith("""["EVENT"""") })
        }
    }

    @Test
    fun noProfileIsCopiedWhileOff() = runBlocking {
        val network = MemoryNetwork()
        val source = "ws://source.test".normalizeRelayUrl()
        val mirror = "ws://mirror.test".normalizeRelayUrl()
        withIolite(network = network, config = mirrorConfig(mirror, social = false)) { iolite ->
            val profile = author.sign(1_700_000_003, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
            readProfile(iolite, network, source, profile)
            delay(100)
            assertTrue(network.relays[mirror.url]?.sent.orEmpty().none { it.startsWith("""["EVENT"""") })
        }
    }

    @Test
    fun aRefusedCopyIsNotSentAgainWhenTheSocketReconnects() = runBlocking {
        val network = MemoryNetwork()
        val source = "ws://source.test".normalizeRelayUrl()
        val mirror = "ws://mirror.test".normalizeRelayUrl()
        withIolite(network = network, config = mirrorConfig(mirror)) { iolite ->
            val profile = author.sign(1_700_000_003, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
            readProfile(iolite, network, source, profile)
            network.awaitSent(mirror.url) { it.contains(profile.id) }
            network.push(mirror.url, JSONArray().put("OK").put(profile.id).put(false).put("blocked: no").toString())
            val before = network.relays.getValue(mirror.url).sent.count { it.contains(profile.id) }
            network.relays.getValue(mirror.url).listener?.onOpen()
            delay(100)
            assertEquals(before, network.relays.getValue(mirror.url).sent.count { it.contains(profile.id) })
        }
    }

    /** Runs a one-shot profile read on [relay] and answers it with [profile]. */
    private suspend fun readProfile(iolite: Iolite, network: MemoryNetwork, relay: RelayUrl, profile: Event) = coroutineScope {
        val read = async {
            withTimeout(5_000) {
                iolite.query(Query.profile(profile.pubkey), QueryOptions.localAndRemote(setOf(relay), RemoteMode.OneShot(2.seconds)))
                    .first { it.settled }
            }
        }
        val req = network.awaitReq(relay.url, Kinds.Profile)
        network.push(relay.url, JSONArray().put("EVENT").put(req).put(profile.toJsonObject()).toString())
        network.push(relay.url, JSONArray().put("EOSE").put(req).toString())
        read.await()
    }

    private fun mirrorConfig(mirror: RelayUrl, social: Boolean = true) =
        IoliteConfig(eoseGrace = 5.milliseconds, oneShotTimeout = 2.seconds, social = social, profileMirror = mirror)

    private suspend fun withIolite(
        network: MemoryNetwork? = null,
        config: IoliteConfig = IoliteConfig(eoseGrace = 5.milliseconds, oneShotTimeout = 2.seconds),
        block: suspend (Iolite) -> Unit,
    ) {
        val dir = File("build/tmp/iolite-social-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            config = config,
            webSocket = network?.factory(),
            signer = device,
            openConnection = ::JdbcSqliteConnection,
        )
        try {
            block(iolite)
        } finally {
            iolite.close()
            scope.coroutineContext.job.cancel()
        }
    }

    private companion object {
        fun key(seed: Int) = LocalSigner(Hex.decode("%064x".format(seed)))
    }
}
