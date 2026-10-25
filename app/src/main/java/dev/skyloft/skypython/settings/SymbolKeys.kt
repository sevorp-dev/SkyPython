package dev.skyloft.skypython.settings

/** One symbol-bar key: [label] shown, [text] inserted. */
data class SymbolKey(val label: String, val text: String)

/**
 * The symbol bar as the user edits it in Settings: keys separated by spaces (so `,` can be a key).
 * `Tab` inserts four spaces; anything else inserts itself, e.g. `self.` or `print(`.
 */
const val DEFAULT_SYMBOL_KEYS = "Tab : ; ' \" # ( ) [ ] { } = . , _ + - * / < > \\ |"

private const val TAB_LABEL = "Tab"

fun parseSymbolKeys(spec: String): List<SymbolKey> {
    val tokens = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return parseSymbolKeys(DEFAULT_SYMBOL_KEYS)
    return tokens
        .map { if (it.equals(TAB_LABEL, ignoreCase = true)) SymbolKey(TAB_LABEL, "    ") else SymbolKey(it, it) }
        .distinctBy { it.label }
}

/** Single-space separated, or the default when [spec] has no keys. */
fun normalizeSymbolKeys(spec: String): String =
    spec.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { DEFAULT_SYMBOL_KEYS }

/** What a key press inserts: [text], with the cursor placed [cursor] chars into it. */
data class SymbolInsertion(val text: String, val cursor: Int)

private val PAIRS = mapOf("(" to ")", "[" to "]", "{" to "}", "\"" to "\"", "'" to "'")

/** With [autoClose], opening brackets and quotes insert the pair with the cursor in between. */
fun symbolInsertion(text: String, autoClose: Boolean): SymbolInsertion {
    val close = PAIRS[text]
    return if (autoClose && close != null) SymbolInsertion(text + close, text.length)
    else SymbolInsertion(text, text.length)
}
