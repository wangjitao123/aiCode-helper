package com.aicode.helper.toolwindow

/**
 * Simple Markdown to HTML converter for chat display.
 */
object MarkdownRenderer {

    fun toHtml(markdown: String): String {
        var text = escapeHtml(markdown)

        // Code blocks: ```lang\n...\n```
        text = Regex("```(\\w*)\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).replace(text) { m ->
            val code = m.groupValues[2]
            """<div class="code-block"><pre><code>$code</code></pre></div>"""
        }

        // Inline code: `code`
        text = Regex("`([^`]+)`").replace(text) { m ->
            """<code class="inline-code">${m.groupValues[1]}</code>"""
        }

        // Bold: **text**
        text = Regex("\\*\\*(.+?)\\*\\*").replace(text) { "<b>${it.groupValues[1]}</b>" }

        // Italic: *text*
        text = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").replace(text) { "<i>${it.groupValues[1]}</i>" }

        // Headers
        text = Regex("^### (.+)$", RegexOption.MULTILINE).replace(text) { """<h4>${it.groupValues[1]}</h4>""" }
        text = Regex("^## (.+)$", RegexOption.MULTILINE).replace(text) { """<h3>${it.groupValues[1]}</h3>""" }
        text = Regex("^# (.+)$", RegexOption.MULTILINE).replace(text) { """<h2>${it.groupValues[1]}</h2>""" }

        // Unordered lists: - item or * item
        text = Regex("^[\\-\\*] (.+)$", RegexOption.MULTILINE).replace(text) { """<li>${it.groupValues[1]}</li>""" }
        text = Regex("(<li>.*?</li>\\n?)+").replace(text) { "<ul>${it.value}</ul>" }

        // Numbered lists: 1. item
        text = Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE).replace(text) { """<oli>${it.groupValues[1]}</oli>""" }
        text = Regex("(<oli>.*?</oli>\\n?)+").replace(text) {
            it.value.replace("<oli>", "<li>").replace("</oli>", "</li>").let { v -> "<ol>$v</ol>" }
        }

        // Line breaks (double newline = paragraph)
        text = text.replace("\n\n", "<br/><br/>")
        text = text.replace("\n", "<br/>")

        return text
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

