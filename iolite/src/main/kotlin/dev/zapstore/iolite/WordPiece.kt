package dev.zapstore.iolite

import java.io.File

/** WordPiece tokenizer matching the enricher's leaf-ir-v1 encoder. */
internal class WordPiece(
    private val ids: Map<String, Int>,
    private val unk: Int,
    private val cls: Int,
    private val sep: Int,
) {
    fun encode(text: String): LongArray {
        val out = ArrayList<Long>(64)
        out.add(cls.toLong())
        for (word in basicTokens(text.lowercase())) {
            for (id in wordPiece(word)) out.add(id)
            if (out.size >= MAX_TOKENS - 1) break
        }
        while (out.size > MAX_TOKENS - 1) out.removeAt(out.lastIndex)
        out.add(sep.toLong())
        return out.toLongArray()
    }

    private fun wordPiece(word: String): LongArray {
        if (word.isEmpty()) return LongArray(0)
        ids[word]?.let { return longArrayOf(it.toLong()) }
        val out = ArrayList<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (end > start) {
                val piece = word.substring(start, end)
                val key = if (start > 0) "##$piece" else piece
                val id = ids[key]
                if (id != null) {
                    found = id
                    break
                }
                val prev = word.offsetByCodePoints(end, -1)
                if (prev == end) break
                end = prev
            }
            if (found == null) return longArrayOf(unk.toLong())
            out.add(found.toLong())
            start = end
        }
        return out.toLongArray()
    }

    companion object {
        private const val MAX_TOKENS = 512

        fun load(file: File): WordPiece {
            val ids = HashMap<String, Int>(30_000)
            file.bufferedReader().useLines { lines ->
                lines.forEach { line -> ids[line] = ids.size }
            }
            fun required(token: String) = ids[token] ?: error("vocab missing $token")
            return WordPiece(ids, required("[UNK]"), required("[CLS]"), required("[SEP]"))
        }
    }
}

private fun basicTokens(text: String): List<String> {
    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val code = text.codePointAt(index)
        index += Character.charCount(code)
        when {
            code == 0 || (isControl(code) && !isSpace(code)) -> Unit
            isCjk(code) -> {
                builder.append(' ')
                builder.appendCodePoint(code)
                builder.append(' ')
            }
            isSpace(code) -> builder.append(' ')
            else -> builder.appendCodePoint(code)
        }
    }
    return builder.split(' ').filter { it.isNotEmpty() }.flatMap(::splitPunct)
}

private fun splitPunct(word: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var index = 0
    while (index < word.length) {
        val code = word.codePointAt(index)
        index += Character.charCount(code)
        if (isPunctOrSymbol(code)) {
            if (current.isNotEmpty()) {
                out.add(current.toString())
                current.clear()
            }
            out.add(Character.toString(code))
        } else {
            current.appendCodePoint(code)
        }
    }
    if (current.isNotEmpty()) out.add(current.toString())
    return out
}

private fun isControl(code: Int): Boolean = Character.getType(code) == Character.CONTROL.toInt()

private fun isSpace(code: Int): Boolean = Character.isWhitespace(code)

private fun isCjk(code: Int): Boolean =
    Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN ||
        code in 0x3040..0x30FF ||
        code in 0x3400..0x4DBF ||
        code in 0x20000..0x2A6DF

private fun isPunctOrSymbol(code: Int): Boolean = when (Character.getType(code)) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    Character.MATH_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(),
    Character.MODIFIER_SYMBOL.toInt(),
    Character.OTHER_SYMBOL.toInt(),
    -> true
    else -> false
}
