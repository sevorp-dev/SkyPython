package dev.skyloft.skypython.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val MIN_FONT_SIZE_SP = 12
const val MAX_FONT_SIZE_SP = 24

fun clampFontSize(sp: Int): Int = sp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)

const val MIN_TERMINAL_FONT_SP = 8
const val MAX_TERMINAL_FONT_SP = 32

fun clampTerminalFontSize(sp: Int): Int = sp.coerceIn(MIN_TERMINAL_FONT_SP, MAX_TERMINAL_FONT_SP)

/** Pure mapping between [EditorSettings] and DataStore [Preferences]. Missing keys fall back to defaults. */
object SettingsMapping {
    private val FONT_SIZE = intPreferencesKey("font_size_sp")
    private val WORD_WRAP = booleanPreferencesKey("word_wrap")
    private val LINE_NUMBERS = booleanPreferencesKey("line_numbers")
    private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    private val CODE_COMPLETION = booleanPreferencesKey("code_completion")
    private val AUTO_CLOSE_PAIRS = booleanPreferencesKey("auto_close_pairs")
    private val SYMBOL_KEYS = stringPreferencesKey("symbol_keys")
    private val KEYBOARD_SUGGESTIONS = booleanPreferencesKey("keyboard_suggestions")
    private val CODE_FONT = stringPreferencesKey("code_font")
    private val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size_sp")
    private val TERMINAL_THEME = stringPreferencesKey("terminal_theme")
    private val BOTTOM_NAV_BAR = booleanPreferencesKey("bottom_nav_bar")
    private val APP_THEME = stringPreferencesKey("app_theme")

    fun read(prefs: Preferences): EditorSettings {
        val d = EditorSettings()
        return EditorSettings(
            fontSizeSp = clampFontSize(prefs[FONT_SIZE] ?: d.fontSizeSp),
            wordWrap = prefs[WORD_WRAP] ?: d.wordWrap,
            lineNumbers = prefs[LINE_NUMBERS] ?: d.lineNumbers,
            keepScreenOn = prefs[KEEP_SCREEN_ON] ?: d.keepScreenOn,
            codeCompletion = prefs[CODE_COMPLETION] ?: d.codeCompletion,
            autoClosePairs = prefs[AUTO_CLOSE_PAIRS] ?: d.autoClosePairs,
            symbolKeys = normalizeSymbolKeys(prefs[SYMBOL_KEYS] ?: d.symbolKeys),
            keyboardSuggestions = prefs[KEYBOARD_SUGGESTIONS] ?: d.keyboardSuggestions,
            codeFont = prefs[CODE_FONT]?.let(CodeFontChoice::fromKey) ?: d.codeFont,
            terminalFontSizeSp = clampTerminalFontSize(prefs[TERMINAL_FONT_SIZE] ?: d.terminalFontSizeSp),
            terminalTheme = prefs[TERMINAL_THEME]?.let(TerminalTheme::fromKey) ?: d.terminalTheme,
            bottomNavBar = prefs[BOTTOM_NAV_BAR] ?: d.bottomNavBar,
            appTheme = prefs[APP_THEME]?.let(AppTheme::fromKey) ?: d.appTheme,
        )
    }

    fun write(prefs: MutablePreferences, settings: EditorSettings) {
        prefs[FONT_SIZE] = clampFontSize(settings.fontSizeSp)
        prefs[WORD_WRAP] = settings.wordWrap
        prefs[LINE_NUMBERS] = settings.lineNumbers
        prefs[KEEP_SCREEN_ON] = settings.keepScreenOn
        prefs[CODE_COMPLETION] = settings.codeCompletion
        prefs[AUTO_CLOSE_PAIRS] = settings.autoClosePairs
        prefs[SYMBOL_KEYS] = normalizeSymbolKeys(settings.symbolKeys)
        prefs[KEYBOARD_SUGGESTIONS] = settings.keyboardSuggestions
        prefs[CODE_FONT] = settings.codeFont.key
        prefs[TERMINAL_FONT_SIZE] = clampTerminalFontSize(settings.terminalFontSizeSp)
        prefs[TERMINAL_THEME] = settings.terminalTheme.key
        prefs[BOTTOM_NAV_BAR] = settings.bottomNavBar
        prefs[APP_THEME] = settings.appTheme.key
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "editor_settings")

/** Persists [EditorSettings] (DataStore Preferences). */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    /** Current settings; emits defaults until something is saved. */
    val settings: Flow<EditorSettings> = store.data.map(SettingsMapping::read).distinctUntilChanged()

    /** Atomically reads, transforms and writes the settings (font sizes are clamped to their ranges). */
    suspend fun update(transform: (EditorSettings) -> EditorSettings) {
        store.edit { prefs -> SettingsMapping.write(prefs, transform(SettingsMapping.read(prefs))) }
    }

    /** [update] in the background, finished even if the screen that asked is gone by then. */
    fun launchUpdate(transform: (EditorSettings) -> EditorSettings) {
        writes.launch { runCatching { update(transform) } }
    }

    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
