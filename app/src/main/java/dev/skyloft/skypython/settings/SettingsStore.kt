package dev.skyloft.skypython.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val MIN_FONT_SIZE_SP = 12
const val MAX_FONT_SIZE_SP = 24

fun clampFontSize(sp: Int): Int = sp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)

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
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "editor_settings")

/** Persists [EditorSettings] (DataStore Preferences). */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    /** Current settings; emits defaults until something is saved. */
    val settings: Flow<EditorSettings> = store.data.map(SettingsMapping::read).distinctUntilChanged()

    /** Atomically reads, transforms and writes the settings (font size is clamped to 12..24). */
    suspend fun update(transform: (EditorSettings) -> EditorSettings) {
        store.edit { prefs -> SettingsMapping.write(prefs, transform(SettingsMapping.read(prefs))) }
    }
}
