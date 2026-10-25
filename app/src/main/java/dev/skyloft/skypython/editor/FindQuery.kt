package dev.skyloft.skypython.editor

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * What the find bar searches for. Every mode is compiled to one Java regex ([pattern]), so the
 * editor's highlighter (Sora's searcher in regex mode) and [replaceAll] always agree on matches.
 */
data class FindQuery(
    val text: String = "",
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
) {
    /** The regex source handed to the editor; null when there is nothing to search for. */
    val pattern: String?
        get() {
            if (text.isEmpty()) return null
            val core = if (regex) text else Pattern.quote(text)
            return if (wholeWord) "\\b(?:$core)\\b" else core
        }

    /** Null when the query is usable; otherwise the regex syntax error to show. */
    val error: String?
        get() = try {
            compile()
            null
        } catch (e: PatternSyntaxException) {
            e.description ?: "Invalid pattern"
        }

    /** Null for an empty or invalid query. Same flags as Sora's searcher (MULTILINE). */
    fun compile(): Pattern? {
        val p = pattern ?: return null
        return Pattern.compile(p, Pattern.MULTILINE or if (matchCase) 0 else Pattern.CASE_INSENSITIVE)
    }

    fun isSearchable(): Boolean = pattern != null && error == null

    /** All non-empty matches in [input] as half-open index ranges [start, end). */
    fun findAll(input: CharSequence): List<IntRange> {
        val p = runCatching { compile() }.getOrNull() ?: return emptyList()
        val m = p.matcher(input)
        val out = ArrayList<IntRange>()
        while (m.find()) {
            if (m.end() > m.start()) out += m.start() until m.end()
        }
        return out
    }

    /**
     * Replacement text for one match whose text is [matched]: [replacement] literally, or with
     * `$1`-style groups expanded in regex mode.
     */
    fun replacementFor(matched: String, replacement: String): String {
        if (!regex) return replacement
        val m = runCatching { compile() }.getOrNull()?.matcher(matched) ?: return replacement
        if (!m.find()) return replacement
        return try {
            val sb = StringBuffer()
            m.appendReplacement(sb, replacement)
            // appendReplacement also copies the text before the match; keep only the replacement.
            sb.substring(m.start())
        } catch (e: RuntimeException) {
            replacement // e.g. "$9" with fewer groups: insert it as typed
        }
    }

    /** [input] with every match replaced, and how many were replaced. */
    fun replaceAll(input: String, replacement: String): Pair<String, Int> {
        val p = runCatching { compile() }.getOrNull() ?: return input to 0
        return try {
            replaceWith(p, input, if (regex) replacement else Matcher.quoteReplacement(replacement))
        } catch (e: RuntimeException) {
            // A bad group reference ("$9" with fewer groups): insert the text as typed.
            replaceWith(p, input, Matcher.quoteReplacement(replacement))
        }
    }

    private fun replaceWith(p: Pattern, input: String, javaReplacement: String): Pair<String, Int> {
        val m = p.matcher(input)
        val sb = StringBuffer(input.length)
        var count = 0
        while (m.find()) {
            if (m.end() == m.start()) continue
            m.appendReplacement(sb, javaReplacement)
            count++
        }
        if (count == 0) return input to 0
        m.appendTail(sb)
        return sb.toString() to count
    }
}

/** Find-bar counter: "3/12" when a match is selected, "–/12" when not, "0" with no matches. */
fun matchCounter(index: Int, count: Int): String = when {
    count <= 0 -> "0"
    index in 0 until count -> "${index + 1}/$count"
    else -> "–/$count"
}
