package dev.skyloft.skypython.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMappingTest {
    @Test
    fun emptyPreferencesGiveDefaults() {
        assertEquals(EditorSettings(), SettingsMapping.read(emptyPreferences()))
    }

    @Test
    fun writeThenReadRoundTrips() {
        val s = EditorSettings(fontSizeSp = 20, wordWrap = false, lineNumbers = false, keepScreenOn = true)
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
    fun clampKeepsInRangeValues() {
        assertEquals(12, clampFontSize(12))
        assertEquals(15, clampFontSize(15))
        assertEquals(24, clampFontSize(24))
        assertEquals(12, clampFontSize(Int.MIN_VALUE))
    }
}
