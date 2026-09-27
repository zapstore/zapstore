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

    /** When false every query is local-only and no relay socket is opened. */
    const val relaysEnabled: Boolean = BuildConfig.RELAYS_ENABLED

    /** Where this install fetches catalog epochs. */
    val catalogRelay: RelayUrl = BuildConfig.CATALOG_RELAY.normalizeRelayUrl()

    private val generalRelays = setOf("wss://relay.primal.net", "wss://relay.damus.io", "wss://nos.lol").toRelayUrls()
    private val profileRelays = setOf("wss://brelay.zapstore.dev", "wss://relay.vertexlab.io").toRelayUrls()
    private val commentRelays = generalRelays + setOf(
        "wss://brelay.zapstore.dev",
        "wss://nostr.wine",
        "wss://relay.snort.social",
        "wss://offchain.pub",
        "wss://nostr.mom",
    ).toRelayUrls()

    /** Where signed events (stacks, preferences, zaps) are published. */
    val writeRelays: Set<RelayUrl> = generalRelays + "wss://brelay.zapstore.dev".normalizeRelayUrl()

    val profileCacheDuration = 1.days
    val zapRetention = 90.days

    /** Kind 0: one shot, cached for a day, author relays added when known. */
    val profileQuery: QueryOptions = remoteOrLocal {
        QueryOptions.localAndRemote(profileRelays, RemoteMode.OneShot(), cachedFor = profileCacheDuration, useAuthorRelays = true)
    }

    /** Kind 9735: one shot, refreshed at most every few minutes. */
    val zapQuery: QueryOptions = remoteOrLocal {
        QueryOptions.localAndRemote(writeRelays, RemoteMode.OneShot(), cachedFor = 5.minutes)
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
