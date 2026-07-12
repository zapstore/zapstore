package dev.zapstore.purplequartz

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class PurpleQuartzConfig(
    val databaseName: String = "purplequartz.db",
    val oneShotTimeout: Duration = 30.seconds,
    val ingestionCapacity: Int = 1_024,
    val expirationSweepInterval: Duration = 1.minutes,
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
    }
}
