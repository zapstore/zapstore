package dev.zapstore.iolite

object Bolt11 {
    fun amountSats(invoice: String): Long {
        val lower = invoice.lowercase()
        val match = Regex("^lnbc(\\d+)([munp]?)").find(lower) ?: return 0
        val amount = match.groupValues[1].toLongOrNull() ?: return 0
        return when (match.groupValues[2]) {
            "m" -> amount * 100_000
            "u" -> amount * 100
            "n" -> amount / 10
            "p" -> amount / 10_000
            "" -> amount * 100_000_000
            else -> 0
        }
    }
}
