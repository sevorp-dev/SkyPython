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
    val terminalTheme: TerminalTheme = TerminalTheme.Auto,
    /** The bottom bar (Editor · Terminal · Packages · Projects · Settings); the ☰ drawer stays either way. */
    val bottomNavBar: Boolean = true,
    /** Light or dark app theme, or the system's choice. */
    val appTheme: AppTheme = AppTheme.System,
)

/** App theme choices (Settings → Appearance → Theme). [key] is what DataStore keeps. */
enum class AppTheme(val key: String) {
    System("system"),
    Dark("dark"),
    Light("light");

    /** Whether the app is dark, given the system's current choice. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Dark -> true
        Light -> false
    }

    companion object {
        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: System
    }
}

/** Code typeface choices. [key] is what DataStore keeps (stable across renames). */
enum class CodeFontChoice(val key: String) {
    JetBrainsMono("jetbrains_mono"),
    SystemMono("system_mono");

    companion object {
        fun fromKey(key: String?): CodeFontChoice = entries.firstOrNull { it.key == key } ?: JetBrainsMono
    }
}

/**
 * Terminal colour themes: the app's console (light or dark with the app), or always light, always
 * dark, or near-black (easier on OLED screens at night).
 */
enum class TerminalTheme(val key: String) {
    // "dark" is historical: it was the app's console when the app was dark only.
    Auto("dark"),
    Light("light"),
    Dark("always_dark"),
    Black("black");

    /** Whether the terminal is light while the app is light ([appLight]) or dark. */
    fun isLight(appLight: Boolean): Boolean = when (this) {
        Auto -> appLight
        Light -> true
        Dark, Black -> false
    }

    companion object {
        fun fromKey(key: String?): TerminalTheme = entries.firstOrNull { it.key == key } ?: Auto
    }
}
