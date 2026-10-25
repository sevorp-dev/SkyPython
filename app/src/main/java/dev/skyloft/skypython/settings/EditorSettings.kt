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
    /** Typeface of the code editor (the console and terminal follow it too). */
    val codeFont: CodeFontChoice = CodeFontChoice.JetBrainsMono,
    /** Text size of the interpreter and the terminal, in sp (see [clampTerminalFontSize]). */
    val terminalFontSizeSp: Int = 14,
    /** Colours of the interpreter and the terminal. */
    val terminalTheme: TerminalTheme = TerminalTheme.Dark,
    /** The bottom bar (Editor · Terminal · Packages · Projects · Settings); the ☰ drawer stays either way. */
    val bottomNavBar: Boolean = true,
)

/** Code typeface choices. [key] is what DataStore keeps (stable across renames). */
enum class CodeFontChoice(val key: String) {
    JetBrainsMono("jetbrains_mono"),
    SystemMono("system_mono");

    companion object {
        fun fromKey(key: String?): CodeFontChoice = entries.firstOrNull { it.key == key } ?: JetBrainsMono
    }
}

/** Terminal colour themes: the app's dark console, or near-black (easier on OLED screens at night). */
enum class TerminalTheme(val key: String) {
    Dark("dark"),
    Black("black");

    companion object {
        fun fromKey(key: String?): TerminalTheme = entries.firstOrNull { it.key == key } ?: Dark
    }
}
