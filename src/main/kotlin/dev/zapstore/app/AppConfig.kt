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

    /** When false every query is local-only and no relay socket is opened. */
    const val relaysEnabled: Boolean = BuildConfig.RELAYS_ENABLED

    /** Bootstrap address for the first catalog fetch when the catalog has no stored relays. */
    val catalogRelay: RelayUrl = BuildConfig.CATALOG_RELAY.normalizeRelayUrl()

    private val generalRelays = setOf("wss://relay.primal.net", "wss://relay.damus.io", "wss://nos.lol").toRelayUrls()
    private val profileRelays = setOf("wss://brelay.zapstore.dev", "wss://relay.vertexlab.io").toRelayUrls()
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

    /** NIP-82 `f` values for this build's ABIs. */
    val platforms: List<String> = supportedAbis.map { "android-$it" }

    private val stackRelays: Set<RelayUrl> = writeRelays + catalogRelay

    val profileCacheDuration = 1.days
    val zapRetention = 90.days

    /** Kind 0: one shot, cached for a day, author relays added when known. */
    val profileQuery: QueryOptions = remoteOrLocal {
        QueryOptions.localAndRemote(profileRelays, RemoteMode.OneShot(), cachedFor = profileCacheDuration, useAuthorRelays = true)
    }

    /** Social stacks, first page: streamed while the Stacks page is open. */
    val socialStacksQuery: QueryOptions = remoteOrLocal { QueryOptions.localAndRemote(stackRelays, RemoteMode.Stream) }

    /** Social stacks, an older page: one shot; results land in SQLite and the page re-reads. */
    val olderStacksQuery: QueryOptions = remoteOrLocal { QueryOptions.localAndRemote(stackRelays, RemoteMode.OneShot()) }

    /** One person's stacks, or one stack by coordinate: one shot through their outbox relays. */
    val authorStacksQuery: QueryOptions = remoteOrLocal {
        QueryOptions.localAndRemote(stackRelays, RemoteMode.OneShot(), cachedFor = 10.minutes, useAuthorRelays = true)
    }

    /** Stacks that list one app: one shot, refreshed at most every ten minutes. */
    val stackedByQuery: QueryOptions = remoteOrLocal {
        QueryOptions.localAndRemote(stackRelays, RemoteMode.OneShot(), cachedFor = 10.minutes)
    }

    /** The catalog key before the launch rotation; stacks made then still name apps under it. */
    const val LEGACY_CATALOG_PUBKEY = "78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d"

    /** Where zap receipts are read, so a zap request asks for them there. */
    val zapRelays: Set<RelayUrl> = writeRelays + catalogRelay

    /** Kind 9735: one shot, refreshed at most every few minutes. */
    val zapQuery: QueryOptions = remoteOrLocal {
        QueryOptions.localAndRemote(zapRelays, RemoteMode.OneShot(), cachedFor = 5.minutes)
    }

    /** Home's activity feed: newest comments and zaps across apps, one shot, refreshed every few minutes. */
    val activityQuery: QueryOptions = remoteOrLocal {
        QueryOptions.localAndRemote(commentRelays + zapRelays, RemoteMode.OneShot(), cachedFor = 5.minutes)
    }

    /** Kind 1111: always LocalAndRemote so comments are fetched even when other relays stay local. */
    val commentQuery: QueryOptions =
        QueryOptions.localAndRemote(commentRelays, RemoteMode.Stream, useAuthorRelays = true)

    /** Kind 0 for comment authors; same always-remote rule as [commentQuery]. */
    val commentProfileQuery: QueryOptions =
        QueryOptions.localAndRemote(profileRelays, RemoteMode.OneShot(), cachedFor = profileCacheDuration, useAuthorRelays = true)

    private inline fun remoteOrLocal(remote: () -> QueryOptions): QueryOptions =
        if (relaysEnabled) remote() else QueryOptions.local()

    private fun Set<String>.toRelayUrls(): Set<RelayUrl> = map(String::normalizeRelayUrl).toSet()
}
