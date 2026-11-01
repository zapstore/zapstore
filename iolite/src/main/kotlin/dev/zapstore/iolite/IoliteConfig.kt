package dev.zapstore.iolite

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class IoliteConfig(
    val databaseName: String = "iolite.db",
    val oneShotTimeout: Duration = 30.seconds,
    val eoseGrace: Duration = 200.milliseconds,
    val ingestionCapacity: Int = 1_024,
    val expirationSweepInterval: Duration = 1.minutes,
    val ingestBatchSize: Int = 100,
    val ingestFlushInterval: Duration = 250.milliseconds,
    val pruneRules: Map<Int, Duration> = emptyMap(),
    /** How long a resolved NIP-65 relay list is trusted before relays are asked again. */
    val relayListCacheFor: Duration = 6.hours,
    val relayListTimeout: Duration = 10.seconds,
    /** Where kind 10002 lists are fetched. Empty falls back to each query's own relays. */
    val indexerRelays: Set<RelayUrl> = emptySet(),
    /** Most outbox relays one read connects to, beyond the query's own relays. */
    val maxConnections: Int = 12,
    /** Most of one user's relays a read or publish uses. */
    val maxRelaysPerUser: Int = 3,
    /** A relay that sends nothing for this long after a `REQ` counts as failed. */
    val firstResponseTimeout: Duration = 10.seconds,
    val liveness: LivenessOptions = LivenessOptions(),
    /** NIP-66 monitor pubkeys whose kind 30166 reports are trusted. Empty turns NIP-66 off. */
    val relayMonitors: Set<String> = emptySet(),
    /** Where the monitors' reports are read. */
    val monitorRelays: Set<RelayUrl> = emptySet(),
    /** A relay no trusted monitor has reported for this long is skipped. */
    val relayReportMaxAge: Duration = 12.hours,
    /** A NIP-47 wallet reply; paying can wait on a route. */
    val walletTimeout: Duration = 60.seconds,
    val maxCompressedBytes: Long = 32L * 1024 * 1024,
    /** Whole tar after zstd. Epoch 0 is every current listing; 20k apps is tens of MB. */
    val maxUncompressedBytes: Long = 512L * 1024 * 1024,
    /** Tar members in one bundle. A from-0 catalog is about three files per app, plus one avatar each. */
    val maxMembers: Int = 100_000,
    /** One JSONL line (one event or delete). Matches the relay's 0.5 MiB event cap. */
    val maxJsonLineBytes: Long = 512L * 1024,
    val maxIconBytes: Long = 256L * 1024,
) {
    internal fun validate() {
        require(databaseName.isNotBlank() && databaseName.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "databaseName must be a single nonblank file name"
        }
        require(oneShotTimeout.isFinite() && oneShotTimeout.isPositive()) {
            "oneShotTimeout must be finite and positive"
        }
        require(eoseGrace.isFinite() && eoseGrace.isPositive()) {
            "eoseGrace must be finite and positive"
        }
        require(ingestionCapacity >= 1) { "ingestionCapacity must be at least one" }
        require(expirationSweepInterval.isFinite() && expirationSweepInterval.isPositive()) {
            "expirationSweepInterval must be finite and positive"
        }
        require(ingestBatchSize >= 1) { "ingestBatchSize must be at least one" }
        require(relayListCacheFor.isFinite() && relayListCacheFor.isPositive()) {
            "relayListCacheFor must be finite and positive"
        }
        require(relayListTimeout.isFinite() && relayListTimeout.isPositive()) {
            "relayListTimeout must be finite and positive"
        }
        require(maxConnections >= 1 && maxRelaysPerUser >= 1) { "relay selection limits must be at least one" }
        require(firstResponseTimeout.isFinite() && firstResponseTimeout.isPositive()) {
            "firstResponseTimeout must be finite and positive"
        }
        require(relayReportMaxAge.isFinite() && relayReportMaxAge.isPositive()) {
            "relayReportMaxAge must be finite and positive"
        }
        require(relayMonitors.all { Hex.isHex(it, 64) }) { "relayMonitors must be lowercase hex pubkeys" }
        liveness.validate()
        require(ingestFlushInterval.isFinite() && ingestFlushInterval.isPositive()) {
            "ingestFlushInterval must be finite and positive"
        }
        require(maxCompressedBytes > 0 && maxUncompressedBytes > 0 && maxMembers > 0) {
            "catalog bundle limits must be positive"
        }
        require(maxJsonLineBytes > 0 && maxJsonLineBytes <= maxUncompressedBytes) {
            "maxJsonLineBytes must be positive and at most maxUncompressedBytes"
        }
        pruneRules.forEach { (kind, maxAge) ->
            require(kind >= 0) { "pruneRules kinds must be non-negative" }
            require(maxAge.isFinite() && maxAge.isPositive()) {
                "pruneRules maxAge must be finite and positive"
            }
        }
    }
}
