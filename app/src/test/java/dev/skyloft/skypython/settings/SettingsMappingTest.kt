package dev.skyloft.skypython.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMappingTest {
    @Test
    fun emptyPreferencesGiveDefaults() {
        assertEquals(EditorSettings(), SettingsMapping.read(emptyPreferences()))
    }

    @Test
    fun codeCompletionIsOnByDefault() {
        assertEquals(true, SettingsMapping.read(emptyPreferences()).codeCompletion)
    }

    @Test
    fun writeThenReadRoundTrips() {
        val s = EditorSettings(fontSizeSp = 20, wordWrap = false, lineNumbers = false, keepScreenOn = true, codeCompletion = false)
        val prefs = mutablePreferencesOf()
        SettingsMapping.write(prefs, s)
        assertEquals(s, SettingsMapping.read(prefs))
    }

    @Test
    fun fontSizeIsClampedOnWriteAndRead() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.write(prefs, EditorSettings(fontSizeSp = 99))
        assertEquals(24, SettingsMapping.read(prefs).fontSizeSp)
        SettingsMapping.write(prefs, EditorSettings(fontSizeSp = 3))
        assertEquals(12, SettingsMapping.read(prefs).fontSizeSp)
        // A stored out-of-range value (e.g. written by an older build) is clamped too.
        prefs[intPreferencesKey("font_size_sp")] = 100
        assertEquals(24, SettingsMapping.read(prefs).fontSizeSp)
    }

    @Test
    fun smartEditingDefaults() {
        val d = SettingsMapping.read(emptyPreferences())
        assertEquals(true, d.autoClosePairs)
        assertEquals(DEFAULT_SYMBOL_KEYS, d.symbolKeys)
        assertEquals(false, d.keyboardSuggestions)
    }

    @Test
    fun keyboardSuggestionsRoundTrip() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.write(prefs, EditorSettings(keyboardSuggestions = true))
        assertEquals(true, SettingsMapping.read(prefs).keyboardSuggestions)
    }

    @Test
    fun autoCloseAndSymbolKeysRoundTrip() {
        val s = EditorSettings(autoClosePairs = false, symbolKeys = ": ( ) self.")
        val prefs = mutablePreferencesOf()
        SettingsMapping.write(prefs, s)
        assertEquals(s, SettingsMapping.read(prefs))
    }

    @Test
    fun symbolKeysAreNormalizedOnWrite() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.write(prefs, EditorSettings(symbolKeys = "  :\n (  "))
        assertEquals(": (", SettingsMapping.read(prefs).symbolKeys)
        SettingsMapping.write(prefs, EditorSettings(symbolKeys = "   "))
        assertEquals(DEFAULT_SYMBOL_KEYS, SettingsMapping.read(prefs).symbolKeys)
    }

    @Test
    fun appearanceDefaults() {
        val d = SettingsMapping.read(emptyPreferences())
        assertEquals(CodeFontChoice.JetBrainsMono, d.codeFont)
        assertEquals(14, d.terminalFontSizeSp)
        assertEquals(TerminalTheme.Dark, d.terminalTheme)
    }

    @Test
    fun appearanceRoundTrips() {
        val s = EditorSettings(codeFont = CodeFontChoice.SystemMono, terminalFontSizeSp = 18, terminalTheme = TerminalTheme.Black)
        val prefs = mutablePreferencesOf()
        SettingsMapping.write(prefs, s)
        assertEquals(s, SettingsMapping.read(prefs))
    }

    @Test
    fun terminalFontSizeIsClamped() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.write(prefs, EditorSettings(terminalFontSizeSp = 99))
        assertEquals(MAX_TERMINAL_FONT_SP, SettingsMapping.read(prefs).terminalFontSizeSp)
        prefs[intPreferencesKey("terminal_font_size_sp")] = 1
        assertEquals(MIN_TERMINAL_FONT_SP, SettingsMapping.read(prefs).terminalFontSizeSp)
    }

    @Test
    fun unknownStoredChoicesFallBackToDefaults() {
        val prefs = mutablePreferencesOf()
        prefs[stringPreferencesKey("code_font")] = "comic_sans"
        prefs[stringPreferencesKey("terminal_theme")] = "neon"
        val s = SettingsMapping.read(prefs)
        assertEquals(CodeFontChoice.JetBrainsMono, s.codeFont)
        assertEquals(TerminalTheme.Dark, s.terminalTheme)
    }

    @Test
    fun choiceKeysAreStable() {
        // Stored in DataStore: renaming an enum constant must not lose the user's choice.
        assertEquals(listOf("jetbrains_mono", "system_mono"), CodeFontChoice.entries.map { it.key })
        assertEquals(listOf("dark", "black"), TerminalTheme.entries.map { it.key })
    }

    @Test
    fun clampKeepsInRangeValues() {
        assertEquals(12, clampFontSize(12))
        assertEquals(15, clampFontSize(15))
        assertEquals(24, clampFontSize(24))
        assertEquals(12, clampFontSize(Int.MIN_VALUE))
    }
}
