package dev.zapstore.iolite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NIP-65 mailboxes, relay selection, NIP-66 reports and routing, ported from applesauce where it has tests. */
class OutboxModelTest {
    @Test
    fun relayUrlsNormalize() {
        assertEquals("wss://relay.damus.io", "wss://relay.damus.io/".normalizeRelayUrl().url)
        assertEquals("wss://relay.damus.io", "WSS://Relay.Damus.IO:443//".normalizeRelayUrl().url)
        assertEquals("ws://192.168.0.194:8080", "ws://192.168.0.194:8080".normalizeRelayUrl().url)
        assertEquals("ws://localhost:4869/ws", "ws://localhost:4869/ws/".normalizeRelayUrl().url)
        assertEquals("ws://localhost/testing", "ws://localhost:80/testing#x".normalizeRelayUrl().url)
        assertEquals("wss://relay.example/nostr?x=1", "wss://relay.example/nostr?x=1".normalizeRelayUrl().url)
        val onion = "ws://437fqnfqtcaquzvs5sd43ugznw7dsoatvtskoowgnpn6q5vqkljcrsyd.onion"
        assertEquals(onion, onion.normalizeRelayUrl().url)
        listOf(
            "",
            "bad",
            "bad wss://nostr.wine",
            "http://nostr.wine",
            "wss://nostr.wine,wss://relayable.com",
            "wss://user:pass@relay.example",
            "wss://",
            "wss://relay.example/" + "a".repeat(5_000),
        ).forEach { assertNull(it, it.relayUrlOrNull()) }
    }

    @Test
    fun mailboxesSplitByMarker() {
        val mailboxes = Mailboxes.parse(
            listOf(
                listOf("r", "wss://both.example"),
                listOf("r", "wss://both.example/"),
                listOf("r", "wss://out.example", "write"),
                listOf("r", "wss://in.example", "read"),
                listOf("r", "bad://inbox.com"),
                listOf("r", "something that is not a url"),
                listOf("r", "wss://a.example,wss://b.example"),
                listOf("relay", "wss://other-tag.example"),
            ),
        )
        assertEquals(listOf("wss://both.example", "wss://out.example"), mailboxes.outboxes.map { it.url })
        assertEquals(listOf("wss://both.example", "wss://in.example"), mailboxes.inboxes.map { it.url })
    }

    @Test
    fun selectionStopsAtMaxRelaysPerUser() {
        val users = listOf(user("a", "r1", "r2", "r3"), user("b", "r1", "r2", "r3"), user("c", "r4"))
        val selected = selectOptimalRelays(users, maxConnections = 3, maxRelaysPerUser = 2)
        assertEquals(listOf(relay("r4")), selected.single { it.pubkey == "c" }.relays)
    }

    @Test
    fun selectionNeverExceedsMaxRelaysPerUser() {
        val users = listOf(user("a", "r1", "r2"), user("b", "r2"), user("c", "r1"))
        val selected = selectOptimalRelays(users, maxConnections = 10, maxRelaysPerUser = 1)
        selected.forEach { assertTrue(it.relays.size <= 1) }
    }

    @Test
    fun selectionCoversEveryoneWithFewRelays() {
        val users = listOf(user("a", "big", "a1"), user("b", "big", "b1"), user("c", "big"), user("d", "d1"))
        val selected = selectOptimalRelays(users, maxConnections = 2, maxRelaysPerUser = 1)
        assertEquals(setOf(relay("big"), relay("d1")), selected.flatMap { it.relays }.toSet())
        assertTrue(selected.all { it.relays.isNotEmpty() })
    }

    @Test
    fun selectionKeepsUsersWithoutRelays() {
        val users = listOf(user("a", "first", "second"), user("b"))
        val selected = selectOptimalRelays(users, maxConnections = 1)
        assertEquals(listOf(relay("first")), selected.single { it.pubkey == "a" }.relays)
        assertEquals(emptyList<RelayUrl>(), selected.single { it.pubkey == "b" }.relays)
        assertEquals(listOf(relay("x")), listOf(user("b")).withFallbackRelays(listOf(relay("x"))).single().relays)
    }

    @Test
    fun relayReportsParseAndExclude() {
        val report = runBlocking {
            MONITOR.sign(
                1_000,
                Kinds.RelayDiscovery,
                listOf(listOf("d", "wss://relay.example/"), listOf("rtt-open", "120"), listOf("R", "payment")),
                "",
            )
        }
        val parsed = RelayReport.parse(report)!!
        assertEquals("wss://relay.example", parsed.relay.url)
        assertTrue(parsed.requiresPayment)

        val standing = RelayStanding.of(
            listOf(
                parsed.copy(requiresPayment = false, createdAt = 900),
                parsed.copy(requiresPayment = false),
            ),
        )!!
        assertEquals(1_000L, standing.lastSeen)
        assertTrue(!standing.excludes(newestSeconds = 1_000, maxAgeSeconds = 60))
        assertTrue(standing.excludes(newestSeconds = 2_000, maxAgeSeconds = 60))
        assertTrue(standing.copy(requiresPayment = true).excludes(newestSeconds = 1_000, maxAgeSeconds = 60))
    }

    @Test
    fun readGoesToAuthorsOutboxesNarrowedPerRelay() {
        val base = relay("base")
        val mailboxes = mapOf(
            "a".pk to Mailboxes(listOf(relay("a-out")), listOf(relay("a-in"))),
            "b".pk to Mailboxes(listOf(relay("b-out")), emptyList()),
        )
        val filter = Filter(authors = listOf("a".pk, "b".pk, "c".pk), kinds = listOf(0))
        val plan = RelayRouting.read(
            filters = listOf(filter),
            base = listOf(base),
            outboxAuthors = listOf("a".pk, "b".pk, "c".pk),
            inboxAuthors = emptyList(),
            mailboxes = mailboxes,
            skip = { false },
            maxConnections = 12,
            maxRelaysPerUser = 3,
        )
        assertEquals(listOf(filter), plan[base])
        assertEquals(listOf("a".pk), plan.getValue(relay("a-out")).single().authors)
        assertEquals(listOf("b".pk), plan.getValue(relay("b-out")).single().authors)
        assertTrue(relay("a-in") !in plan)
    }

    @Test
    fun readSkipsUnhealthyRelaysButNeverEmptiesBase() {
        val base = relay("base")
        val mailboxes = mapOf("a".pk to Mailboxes(listOf(relay("down"), relay("up")), listOf(relay("inbox"))))
        val filter = Filter(kinds = listOf(1111))
        val plan = RelayRouting.read(
            filters = listOf(filter),
            base = listOf(base),
            outboxAuthors = emptyList(),
            inboxAuthors = listOf("a".pk),
            mailboxes = mailboxes,
            skip = { it == base || it == relay("down") },
            maxConnections = 12,
            maxRelaysPerUser = 3,
        )
        assertEquals(setOf(base, relay("inbox")), plan.keys)
    }

    @Test
    fun writeGoesToOwnOutboxesAndTaggedInboxes() = runBlocking {
        val author = MONITOR.publicKey
        val event = MONITOR.sign(1, 1111, listOf(listOf("p", "b".pk), listOf("p", author)), "hi")
        val mailboxes = mapOf(
            author to Mailboxes(listOf(relay("mine")), listOf(relay("my-inbox"))),
            "b".pk to Mailboxes(listOf(relay("b-out")), listOf(relay("b-in"), relay("b-in-down"))),
        )
        val destinations = RelayRouting.write(
            event = event,
            requested = listOf(relay("asked")),
            mailboxes = mailboxes,
            fallback = listOf(relay("fallback")),
            skip = { it == relay("b-in-down") },
            maxRelaysPerUser = 3,
        )
        assertEquals(setOf(relay("asked"), relay("mine"), relay("b-in")), destinations)

        val unknown = RelayRouting.write(event, emptyList(), emptyMap(), listOf(relay("fallback")), { true }, 3)
        assertEquals(setOf(relay("fallback")), unknown)
    }

    private fun user(pubkey: String, vararg relays: String) = RelayPointer(pubkey, relays.map(::relay))

    private fun relay(name: String) = "wss://$name.example".normalizeRelayUrl()

    private val String.pk: String get() = first().toString().repeat(64).replace(Regex("[^0-9a-f]"), "f")

    companion object {
        private val MONITOR = LocalSigner(Hex.decode("0000000000000000000000000000000000000000000000000000000000000002"))
    }
}
