package dev.zapstore.app

import dev.zapstore.iolite.QueryOptions
import dev.zapstore.iolite.RelayUrl
import dev.zapstore.iolite.RemoteMode
import dev.zapstore.iolite.normalizeRelayUrl
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Build-time endpoints and the query options each kind of data uses. */
object AppConfig {
    /** The only native ABI this app ships and matches catalog assets against. */
    val supportedAbis = listOf("arm64-v8a")

    const val SOURCE_URL = "https://github.com/zapstore/zapstore"

    /** Bootstrap address for the first catalog fetch when the catalog has no stored relays. */
    val catalogRelay: RelayUrl = BuildConfig.CATALOG_RELAY.normalizeRelayUrl()

    /** The key the default catalog must be signed by. Null only in a debug build against a local relay. */
    val catalogPubkey: String? = BuildConfig.CATALOG_PUBKEY.ifEmpty { null }

    /** Signs Vertex's kind 6312 and 7000 answers. */
    const val VERTEX_PUBKEY = "5fc48ac4765ff81e9c51014b9d2f2c91621370f4c6b5452a9c06456e4cccaeb4"

    private val generalRelays = setOf("wss://relay.primal.net", "wss://relay.damus.io", "wss://nos.lol").toRelayUrls()
    private val profileRelays = setOf("wss://brelay.zapstore.dev", "wss://relay.vertexlab.io", "wss://purplepag.es", "wss://relay.primal.net").toRelayUrls()
    private val commentRelays = generalRelays + setOf(
        "wss://brelay.zapstore.dev",
        "wss://nostr.wine",
        "wss://relay.snort.social",
        "wss://offchain.pub",
        "wss://nostr.mom",
    ).toRelayUrls() + catalogRelay

    /** Where comments are published: the relays comments are read from. */
    val commentWriteRelays: Set<RelayUrl> get() = commentRelays

    /** Where signed events (stacks, preferences, zaps) are published. */
    val writeRelays: Set<RelayUrl> = generalRelays + "wss://brelay.zapstore.dev".normalizeRelayUrl()

    /** Where NIP-65 relay lists are fetched; never the Zapstore relays, which do not carry them. */
    /** Where the first-install web-of-trust check is asked (Vertex DVM). */
    val vertexRelays: Set<RelayUrl> = setOf("wss://relay.vertexlab.io").toRelayUrls()

    val indexerRelays: Set<RelayUrl> = setOf("wss://relay.vertexlab.io", "wss://purplepag.es").toRelayUrls()

    /** NIP-66 monitors whose kind 30166 reports exclude down or paid relays: nostr.watch's Fisherman and US-East probe. */
    val relayMonitors: Set<String> = setOf(
        "58faef87b34208c74ed36008eb3621a2a91f360c075c83b88e5348ea949df2d7",
        "9ba6484003e8e88600f97ebffd897b2fe82753082e8e0cd8ea19aac0ff2b712b",
    )

    /** Where [relayMonitors] publish their reports. */
    val monitorRelays: Set<RelayUrl> = setOf("wss://relay.nostr.watch", "wss://relaypag.es").toRelayUrls()

    /** NIP-82 `f` values for this build's ABIs. */
    val platforms: List<String> = supportedAbis.map { "android-$it" }

    private val stackRelays: Set<RelayUrl> = writeRelays + catalogRelay

    val profileCacheDuration = 1.days
    /** Kind 0: one shot, cached for a day, author relays added when known. */
    val profileQuery: QueryOptions = QueryOptions.localAndRemote(profileRelays, RemoteMode.OneShot(), cachedFor = profileCacheDuration, useAuthorRelays = true)

    /** Social stacks, first page: streamed while the Stacks page is open. */
    val socialStacksQuery: QueryOptions = QueryOptions.localAndRemote(stackRelays, RemoteMode.Stream)

    /** Social stacks, an older page: one shot; results land in SQLite and the page re-reads. */
    val olderStacksQuery: QueryOptions = QueryOptions.localAndRemote(stackRelays, RemoteMode.OneShot())

    /** One person's stacks, or one stack by coordinate: one shot through their outbox relays. */
    val authorStacksQuery: QueryOptions = QueryOptions.localAndRemote(stackRelays, RemoteMode.OneShot(), cachedFor = 10.minutes, useAuthorRelays = true)

    /** Stacks that list one app: one shot, refreshed at most every ten minutes. */
    val stackedByQuery: QueryOptions = QueryOptions.localAndRemote(stackRelays, RemoteMode.OneShot(), cachedFor = 10.minutes)

    /** The catalog key before the launch rotation; stacks made then still name apps under it. */
    const val LEGACY_CATALOG_PUBKEY = "78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d"

    /** Where zap receipts are read, so a zap request asks for them there. */
    val zapRelays: Set<RelayUrl> = writeRelays + catalogRelay

    /**
     * Kind 9735 on an app page: streamed like its comments, so the feed fills in as receipts arrive,
     * and read from the inboxes of the catalog key and the C1 owner or listing signer as well.
     */
    val zapQuery: QueryOptions = QueryOptions.localAndRemote(zapRelays, RemoteMode.Stream, useAuthorRelays = true)

    /**
     * Home's activity feed: newest comments and zaps across apps, streamed while Home is open so new ones
     * arrive as they are published, and read from the catalog key's inboxes as well.
     */
    val activityQuery: QueryOptions = QueryOptions.localAndRemote(commentRelays + zapRelays, RemoteMode.Stream, useAuthorRelays = true)

    /** The stacks Home's activity feed shows as changed: one shot, refreshed every few minutes. */
    val activityStacksQuery: QueryOptions = QueryOptions.localAndRemote(stackRelays, RemoteMode.OneShot(), cachedFor = 5.minutes)

    /**
     * One person's activity (their comments and zaps): one shot through their outbox relays and the
     * relays comments and receipts are read from, refreshed every few minutes.
     */
    val profileActivityQuery: QueryOptions = QueryOptions.localAndRemote(commentRelays + zapRelays, RemoteMode.OneShot(), cachedFor = 5.minutes, useAuthorRelays = true)

    /** Kind 1111: always LocalAndRemote so comments are fetched even when other relays stay local. */
    val commentQuery: QueryOptions =
        QueryOptions.localAndRemote(commentRelays, RemoteMode.Stream, useAuthorRelays = true)

    /** Kind 0 for comment authors; same always-remote rule as [commentQuery]. */
    val commentProfileQuery: QueryOptions =
        QueryOptions.localAndRemote(profileRelays, RemoteMode.OneShot(), cachedFor = profileCacheDuration, useAuthorRelays = true)

    private fun Set<String>.toRelayUrls(): Set<RelayUrl> = map(String::normalizeRelayUrl).toSet()
}
