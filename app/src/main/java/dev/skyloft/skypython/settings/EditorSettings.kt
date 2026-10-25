package dev.skyloft.skypython.settings

/** Editor preferences shared by the editor (reads) and the Settings screen (writes). */
data class EditorSettings(
    val fontSizeSp: Int = 15,
    val wordWrap: Boolean = true,
    val lineNumbers: Boolean = true,
    val keepScreenOn: Boolean = false,
    /** Suggestions while typing and error underlines (the language server). */
    val codeCompletion: Boolean = true,
    /** Typing an opening bracket or quote also inserts the closing one. */
    val autoClosePairs: Boolean = true,
    /** Symbol-bar keys, space separated (see [parseSymbolKeys]). */
    val symbolKeys: String = DEFAULT_SYMBOL_KEYS,
    /** Let the soft keyboard suggest and autocorrect words; off keeps code intact. */
    val keyboardSuggestions: Boolean = false,
)
