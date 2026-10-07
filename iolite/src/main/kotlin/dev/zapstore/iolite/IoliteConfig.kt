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
