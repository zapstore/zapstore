package dev.zapstore.app

/** GitHub-style `:shortcode:` emoji. Text inside backticks is left alone; unknown codes stay as written. */
object Emoji {
    private val shortcode = Regex(":([a-z0-9_+\\-]+):")

    private val table: Map<String, String> by lazy {
        val stream = Emoji::class.java.getResourceAsStream("/emoji_shortcodes.txt") ?: return@lazy emptyMap()
        stream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val split = line.indexOf(' ')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }.toMap()
        }
    }

    fun replace(text: String): String {
        if (text.indexOf(':') < 0) return text
        return text.split('`').mapIndexed { index, part ->
            if (index % 2 == 1) part
            else shortcode.replace(part) { match -> table[match.groupValues[1]] ?: match.value }
        }.joinToString("`")
    }
}
