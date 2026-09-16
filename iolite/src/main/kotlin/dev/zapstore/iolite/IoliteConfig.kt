package dev.zapstore.iolite

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class IoliteConfig(
    val databaseName: String = "iolite.db",
    val oneShotTimeout: Duration = 30.seconds,
    val ingestionCapacity: Int = 1_024,
    val expirationSweepInterval: Duration = 1.minutes,
    val ingestBatchSize: Int = 100,
    val ingestFlushInterval: Duration = 250.milliseconds,
    /**
     * Retention policy for unbounded regular kinds, as `kind -> maxAge`.
     * Each expiration sweep deletes events of these kinds whose
     * `created_at` is older than the configured age. Addressable kinds
     * need no rule — supersession already bounds them.
     */
    val pruneRules: Map<Int, Duration> = emptyMap(),
) {
    internal fun validate() {
        require(databaseName.isNotBlank() && databaseName.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "databaseName must be a single nonblank file name"
        }
        require(oneShotTimeout.isFinite() && oneShotTimeout.isPositive()) {
            "oneShotTimeout must be finite and positive"
        }
        require(ingestionCapacity >= 1) { "ingestionCapacity must be at least one" }
        require(expirationSweepInterval.isFinite() && expirationSweepInterval.isPositive()) {
            "expirationSweepInterval must be finite and positive"
        }
        require(ingestBatchSize >= 1) { "ingestBatchSize must be at least one" }
        require(ingestFlushInterval.isFinite() && ingestFlushInterval.isPositive()) {
            "ingestFlushInterval must be finite and positive"
        }
        pruneRules.forEach { (kind, maxAge) ->
            require(kind >= 0) { "pruneRules kinds must be non-negative" }
            require(maxAge.isFinite() && maxAge.isPositive()) {
                "pruneRules maxAge must be finite and positive"
            }
        }
    }
}
