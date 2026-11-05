package dev.zapstore.iolite

object Bolt11 {
    private val AMOUNT = Regex("^(\\d+)([munp]?)$")

    /**
     * The mainnet invoice amount in millisats, or null when it has none or does not parse. Only the
     * human-readable part before the last `1` is read: the data part may start with digits or a multiplier.
     */
    fun amountMsat(invoice: String): Long? {
        val lower = invoice.trim().lowercase().removePrefix("lightning:")
        val separator = lower.lastIndexOf('1')
        if (separator < 0) return null
        val hrp = lower.substring(0, separator)
        if (!hrp.startsWith("lnbc")) return null
        val match = AMOUNT.find(hrp.removePrefix("lnbc")) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        return try {
            when (match.groupValues[2]) {
                "m" -> Math.multiplyExact(amount, 100_000_000L)
                "u" -> Math.multiplyExact(amount, 100_000L)
                "n" -> Math.multiplyExact(amount, 100L)
                // BOLT11: a pico amount must be whole millisats.
                "p" -> if (amount % 10 == 0L) amount / 10 else null
                else -> Math.multiplyExact(amount, 100_000_000_000L)
            }
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** [amountMsat] in whole sats, rounded down; 0 when there is none. */
    fun amountSats(invoice: String): Long = (amountMsat(invoice) ?: 0) / 1_000
}
