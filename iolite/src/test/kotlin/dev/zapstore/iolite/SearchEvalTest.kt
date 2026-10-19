package dev.zapstore.iolite

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEvalTest {
    @Test
    fun ranksTheSharedFixture() {
        val file = fixtureFile()
        val root = JSONObject(file.readText())
        val vectors = root.getJSONObject("vectors")
        val apps = root.getJSONArray("apps").objects().map { app ->
            EvalApp(
                id = app.getString("id"),
                name = app.getString("name"),
                bits = bits(app.optJSONArray("facts")),
                keywords = app.optJSONArray("keywords")?.strings().orEmpty(),
                vector = vector(vectors, app.optString("vector", "")),
            )
        }
        val recalls = mutableMapOf(12 to mutableListOf<Double>(), 20 to mutableListOf(), 50 to mutableListOf(), 100 to mutableListOf())
        val reciprocal = mutableListOf<Double>()
        val mismatches = mutableListOf<String>()
        val started = System.nanoTime()
        for (item in root.getJSONArray("cases").objects()) {
            val query = normalizeSearchQuery(item.getString("query"))
            val parsed = parseSearchQuery(query)
            val wanted = bits(item.optJSONArray("facts"))
            assertEquals(item.getString("query"), wanted, parsed.boost or parsed.penalty)
            val mask = bits(item.optJSONArray("filters")) or parsed.hard
            val rows = apps.filter { it.bits and mask == mask && it.bits and parsed.exclude == 0 }
            val ranked = rank(rows, parsed.residual, vector(vectors, item.optString("vector", "")), parsed.boost, parsed.penalty, mask != 0)
            val top = item.getJSONArray("top").strings()
            if (top != ranked) mismatches += "${item.getString("query")}: expected $top, ranked $ranked"
            val relevant = item.optJSONArray("relevant")?.strings().orEmpty()
            if (relevant.isEmpty()) continue
            for (k in listOf(12, 20, 50, 100)) recalls.getValue(k) += recall(ranked, relevant, k)
            reciprocal += reciprocalRank(ranked, relevant)
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
        val latencyMs = (System.nanoTime() - started) / 1_000_000.0
        val vectorBytes = apps.sumOf { it.vector?.size ?: 0 }
        val drift = drift(root.getJSONObject("quantization"))
        println(
            "search eval: recall@12=${mean(recalls.getValue(12))} @20=${mean(recalls.getValue(20))} @50=${mean(recalls.getValue(50))} @100=${mean(recalls.getValue(100))} mrr=${mean(reciprocal)} latency=${"%.1f".format(latencyMs)}ms bytes=$vectorBytes drift=${"%.6f".format(drift)}",
        )
    }
}

private data class EvalApp(val id: String, val name: String, val bits: Int, val keywords: List<String>, val vector: ByteArray?)

private fun rank(apps: List<EvalApp>, query: String, vector: ByteArray?, boost: Int, penalty: Int, filtered: Boolean): List<String> {
    val ranker = SearchRanker(
        query = query,
        queryVector = vector,
        limit = null,
        boost = boost,
        penalty = penalty,
        admitAll = filtered || query.isEmpty(),
    )
    for (app in apps) {
        ranker.consider(app.id.encodeToByteArray(), app.id, app.name, app.vector, app.bits, app.keywords)
    }
    return ranker.ids().map { it.decodeToString() }
}

private fun vector(vectors: JSONObject, name: String): ByteArray? {
    if (name.isEmpty()) return null
    val spec = vectors.getJSONObject(name)
    val out = ByteArray(VECTOR_DIMS)
    val at = spec.getInt("at")
    val len = spec.getInt("len")
    val value = spec.getInt("value").toByte()
    for (i in 0 until len) out[at + i] = value
    return out
}

private fun bits(names: JSONArray?): Int {
    if (names == null) return 0
    var mask = 0
    for (name in names.strings()) {
        mask = mask or when (name) {
            "open_source" -> FACT_SOURCE_AVAILABLE
            "google_services" -> FACT_GOOGLE
            "offline_capable" -> FACT_OFFLINE
            "e2ee" -> FACT_E2EE
            else -> 0
        }
    }
    return mask
}

private fun recall(ranked: List<String>, relevant: List<String>, k: Int): Double {
    val top = ranked.take(k).toSet()
    return relevant.count { it in top }.toDouble() / relevant.size
}

private fun reciprocalRank(ranked: List<String>, relevant: List<String>): Double {
    val at = ranked.indexOfFirst { it in relevant }
    return if (at < 0) 0.0 else 1.0 / (at + 1)
}

private fun mean(values: List<Double>): String {
    if (values.isEmpty()) return "n/a"
    return "%.3f".format(values.average())
}

private fun drift(sample: JSONObject): Double {
    val query = sample.getJSONArray("query").doubles()
    val document = sample.getJSONArray("document").doubles()
    val raw = cosine(query, document)
    val restored = cosine(query.map(::restore), document.map(::restore))
    return abs(raw - restored)
}

private fun restore(value: Double): Double = quantize(value) * 0.3 / 127.0

private fun quantize(value: Double): Int {
    val clipped = min(0.3, max(-0.3, value))
    val rounded = round(clipped / 0.3 * 127.0).toInt()
    return min(127, max(-127, rounded))
}

private fun cosine(left: List<Double>, right: List<Double>): Double {
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    for (i in left.indices) {
        dot += left[i] * right[i]
        leftNorm += left[i] * left[i]
        rightNorm += right[i] * right[i]
    }
    return dot / sqrt(leftNorm * rightNorm)
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

private fun JSONArray.doubles(): List<Double> = List(length()) { getDouble(it) }

private fun fixtureFile(): File {
    val start = System.getProperty("user.dir") ?: error("user.dir is unset")
    var dir = File(start).canonicalFile
    repeat(8) {
        val file = File(dir, "tests/fixtures/search.json")
        if (file.isFile) return file
        dir = dir.parentFile ?: return@repeat
    }
    error("tests/fixtures/search.json not found from ${System.getProperty("user.dir")}")
}
