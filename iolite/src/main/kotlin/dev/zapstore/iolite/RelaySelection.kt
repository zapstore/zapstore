package dev.zapstore.iolite

/** A pubkey and the relays to reach it on, in preference order. */
data class RelayPointer(val pubkey: String, val relays: List<RelayUrl>)

/**
 * Picks at most [maxConnections] relays that cover the most [users], greedily: each round takes the
 * relay that reaches the most users still uncovered. A user
 * leaves the pool once [maxRelaysPerUser] of their relays are picked. Ties go to the relay that appears
 * first among the users left, so the result is deterministic.
 *
 * Returns every user, each with only the picked relays in pick order, capped at [maxRelaysPerUser].
 */
fun selectOptimalRelays(
    users: List<RelayPointer>,
    maxConnections: Int,
    maxRelaysPerUser: Int? = null,
): List<RelayPointer> {
    val pool = users.filter { it.relays.isNotEmpty() }

    // Uncovered pool users per unpicked relay, kept current as users leave the pool.
    val coverage = LinkedHashMap<RelayUrl, Int>()
    val relayUsers = HashMap<RelayUrl, MutableList<Int>>()
    // Where a relay first appears among pool users, encoded as user index * stride + position.
    val order = HashMap<RelayUrl, Long>()
    val stride = pool.maxOfOrNull { it.relays.size }?.toLong() ?: 1L
    val inPool = BooleanArray(pool.size) { true }
    var poolSize = pool.size

    pool.forEachIndexed { index, user ->
        user.relays.forEachIndexed { position, relay ->
            coverage.merge(relay, 1, Int::plus)
            order.putIfAbsent(relay, index * stride + position)
            val list = relayUsers.getOrPut(relay) { mutableListOf() }
            if (list.lastOrNull() != index) list += index
        }
    }

    fun updateOrder(relay: RelayUrl) {
        for (index in relayUsers.getValue(relay)) {
            if (!inPool[index]) continue
            order[relay] = index * stride + pool[index].relays.indexOf(relay)
            return
        }
    }

    val selectionCount = HashMap<String, Int>()
    val selection = LinkedHashSet<RelayUrl>()
    while (poolSize > 0 && selection.size < maxConnections && coverage.isNotEmpty()) {
        var relay: RelayUrl? = null
        var best = 0
        for ((candidate, count) in coverage) {
            if (relay == null || count > best || (count == best && order.getValue(candidate) < order.getValue(relay))) {
                best = count
                relay = candidate
            }
        }
        val picked = relay ?: break
        selection += picked
        coverage.remove(picked)

        if (maxRelaysPerUser == null) continue
        for (index in relayUsers.getValue(picked)) {
            if (!inPool[index]) continue
            val user = pool[index]
            val count = (selectionCount[user.pubkey] ?: 0) + 1
            selectionCount[user.pubkey] = count
            if (count < maxRelaysPerUser) continue

            inPool[index] = false
            poolSize--
            for (other in user.relays) {
                if (other in selection) continue
                val left = coverage.getValue(other) - 1
                if (left == 0) {
                    coverage.remove(other)
                } else {
                    coverage[other] = left
                    if (order.getValue(other) / stride == index.toLong()) updateOrder(other)
                }
            }
        }
    }

    return users.map { user ->
        val relays = selection.filter { it in user.relays }
        user.copy(relays = if (maxRelaysPerUser != null) relays.take(maxRelaysPerUser) else relays)
    }
}

/** Gives [fallbacks] to every user without relays. */
fun List<RelayPointer>.withFallbackRelays(fallbacks: List<RelayUrl>): List<RelayPointer> =
    map { if (it.relays.isEmpty()) it.copy(relays = fallbacks) else it }

/** The pubkeys to ask each relay about, in first-seen order. */
fun List<RelayPointer>.groupByRelay(): Map<RelayUrl, List<String>> {
    val grouped = LinkedHashMap<RelayUrl, MutableList<String>>()
    for (user in this) for (relay in user.relays) grouped.getOrPut(relay) { mutableListOf() } += user.pubkey
    return grouped
}
