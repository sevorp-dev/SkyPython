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

/**
 * The symbol-keys field (Settings) against the stored value: the field keeps its own text (spaces
 * still being typed must not be normalized away), knows what is not saved yet, and shows a change
 * made elsewhere but not the echo of its own save.
 */
class SymbolKeysDraft(saved: String) {
    var text: String = saved
        private set

    /** The last value seen in the store or sent to it (normalized). */
    private var stored = saved

    fun edit(text: String) {
        this.text = text
    }

    /** The text to save, or null when the store has it already. */
    fun toSave(): String? = text.takeIf { normalizeSymbolKeys(it) != stored }

    /** [spec] is on its way to the store. */
    fun saving(spec: String) {
        stored = normalizeSymbolKeys(spec)
    }

    fun storeChanged(saved: String) {
        if (saved == stored) return
        stored = saved
        text = saved
    }
}
