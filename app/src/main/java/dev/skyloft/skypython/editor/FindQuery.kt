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

    /**
     * Null when the query is usable; otherwise the regex engine's own (technical, English)
     * description of the syntax error, possibly empty. Shown after a localized label: see
     * [findErrorText].
     */
    val error: String?
        get() = try {
            compile()
            null
        } catch (e: PatternSyntaxException) {
            e.description.orEmpty()
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
    /**
     * Replacement text for the match at [start]..[end] of [input] (the whole text, so anchors,
     * `` and lookaround see what is around the match, exactly as [replaceAll] does):
     * [replacement] literally, or with `$1`-style groups expanded in regex mode.
     */
    fun replacementFor(input: CharSequence, start: Int, end: Int, replacement: String): String {
        if (!regex) return replacement
        val m = runCatching { compile() }.getOrNull()?.matcher(input) ?: return replacement
        // The same match replaceAll would see: searched for from its start in the full text.
        if (!m.find(start) || m.start() != start || m.end() != end) return replacement
        return try {
            val sb = StringBuffer() // appendReplacement(StringBuilder) needs API 34
            m.appendReplacement(sb, replacement)
            // appendReplacement also copies the text before the match; keep only the replacement.
            sb.substring(start)
        } catch (e: RuntimeException) {
            replacement // e.g. "$9" with fewer groups: insert it as typed
        }
    }

    /**
     * [input] with every match replaced, and how many were replaced. [checkCancelled] is called
     * as the regex reads the text and may throw to stop a runaway pattern.
     */
    fun replaceAll(input: String, replacement: String, checkCancelled: () -> Unit = {}): Pair<String, Int> {
        val p = runCatching { compile() }.getOrNull() ?: return input to 0
        val text: CharSequence = CancellableText(input, checkCancelled)
        return try {
            replaceWith(p, text, if (regex) replacement else Matcher.quoteReplacement(replacement))
        } catch (e: IllegalArgumentException) {
            // A bad group reference ("$9" with fewer groups): insert the text as typed.
            replaceWith(p, text, Matcher.quoteReplacement(replacement))
        } catch (e: IndexOutOfBoundsException) {
            replaceWith(p, text, Matcher.quoteReplacement(replacement))
        }
    }

    private fun replaceWith(p: Pattern, input: CharSequence, javaReplacement: String): Pair<String, Int> {
        val m = p.matcher(input)
        val sb = StringBuffer(input.length)
        var count = 0
        while (m.find()) {
            if (m.end() == m.start()) continue
            m.appendReplacement(sb, javaReplacement)
            count++
        }
        if (count == 0) return input.toString() to 0
        m.appendTail(sb)
        return sb.toString() to count
    }
}

/**
 * [text] for a regex that may run away: every so many reads, [check] runs (and may throw).
 * The regex engine reads only through [get], so a stuck match is stopped there.
 */
private class CancellableText(private val text: String, private val check: () -> Unit) : CharSequence {
    private var reads = 0
    override val length: Int get() = text.length
    override fun get(index: Int): Char {
        if (++reads and 0xFFF == 0) check()
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
    override fun toString(): String = text
}

/** The find bar's error line: the localized [label], then the regex engine's [detail] if any. */
fun findErrorText(label: String, detail: String): String = if (detail.isBlank()) label else "$label: $detail"

/** Find-bar counter: "3/12" when a match is selected, "–/12" when not, "0" with no matches. */
fun matchCounter(index: Int, count: Int): String = when {
    count <= 0 -> "0"
    index in 0 until count -> "${index + 1}/$count"
    else -> "–/$count"
}
