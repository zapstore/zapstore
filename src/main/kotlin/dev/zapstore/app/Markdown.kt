package dev.zapstore.app

import android.text.Html
import android.text.Spanned

object ZapMarkdown {
    fun parse(value: String): Spanned =
        Html.fromHtml(toHtml(value), Html.FROM_HTML_MODE_LEGACY)

    private fun toHtml(value: String): String {
        val lines = value.lines()
        val html = StringBuilder()
        var inUnorderedList = false
        var inOrderedList = false

        fun closeLists() {
            if (inUnorderedList) {
                html.append("</ul>")
                inUnorderedList = false
            }
            if (inOrderedList) {
                html.append("</ol>")
                inOrderedList = false
            }
        }

        lines.forEach { line ->
            val trimmed = line.trim()
            val unordered = trimmed.matches(Regex("^[-*+]\\s+.+"))
            val ordered = trimmed.matches(Regex("^\\d+[.)]\\s+.+"))

            when {
                trimmed.isEmpty() -> {
                    closeLists()
                    html.append("<br>")
                }

                unordered -> {
                    if (!inUnorderedList) {
                        closeLists()
                        html.append("<ul>")
                        inUnorderedList = true
                    }
                    html.append("<li>").append(inline(trimmed.replaceFirst(Regex("^[-*+]\\s+"), ""))).append("</li>")
                }

                ordered -> {
                    if (!inOrderedList) {
                        closeLists()
                        html.append("<ol>")
                        inOrderedList = true
                    }
                    html.append("<li>").append(inline(trimmed.replaceFirst(Regex("^\\d+[.)]\\s+"), ""))).append("</li>")
                }

                else -> {
                    closeLists()
                    val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
                    if (heading != null) {
                        val level = heading.groupValues[1].length
                        html.append("<h").append(level).append(">")
                            .append(inline(heading.groupValues[2]))
                            .append("</h").append(level).append(">")
                    } else {
                        html.append(inline(trimmed)).append("<br>")
                    }
                }
            }
        }
        closeLists()
        return html.toString()
    }

    private fun inline(value: String): String {
        var result = Html.escapeHtml(value)
        result = result.replace(Regex("`([^`]+)`")) { "<tt>${it.groupValues[1]}</tt>" }
        result = result.replace(Regex("""\[([^\]]+)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")) {
            val label = it.groupValues[1]
            val url = it.groupValues[2]
            """<a href="${Html.escapeHtml(url)}">$label</a>"""
        }
        result = result.replace(Regex("""(\*\*|__)(.+?)\1""")) { "<b>${it.groupValues[2]}</b>" }
        result = result.replace(Regex("""(?<!\w)(\*|_)([^*_]+)\1""")) { "<i>${it.groupValues[2]}</i>" }
        return result
    }
}
