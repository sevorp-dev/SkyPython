package dev.skyloft.skypython.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolKeysTest {
    @Test
    fun defaultSpecParsesToTheDefaultBar() {
        val keys = parseSymbolKeys(DEFAULT_SYMBOL_KEYS)
        assertEquals(SymbolKey("Tab", "    "), keys.first())
        assertTrue(keys.any { it.label == "," && it.text == "," })
        assertTrue(keys.any { it.label == "\"" })
        assertEquals(24, keys.size)
    }

    @Test
    fun keysAreSeparatedByWhitespaceSoCommaIsAKey() {
        assertEquals(listOf(",", "(", "self."), parseSymbolKeys("  ,  (\n self. ").map { it.text })
    }

    @Test
    fun duplicatesAreDropped() {
        assertEquals(listOf(":", "("), parseSymbolKeys(": ( : (").map { it.label })
    }

    @Test
    fun blankSpecFallsBackToDefault() {
        assertEquals(parseSymbolKeys(DEFAULT_SYMBOL_KEYS), parseSymbolKeys("   "))
    }

    @Test
    fun tabIsCaseInsensitive() {
        assertEquals(SymbolKey("Tab", "    "), parseSymbolKeys("tab").single())
    }

    @Test
    fun normalizeCollapsesWhitespace() {
        assertEquals(": ( )", normalizeSymbolKeys("  :\n(   ) "))
        assertEquals(DEFAULT_SYMBOL_KEYS, normalizeSymbolKeys(""))
    }

    @Test
    fun openingBracketsAndQuotesInsertPairsWhenAutoCloseIsOn() {
        assertEquals(SymbolInsertion("()", 1), symbolInsertion("(", autoClose = true))
        assertEquals(SymbolInsertion("[]", 1), symbolInsertion("[", autoClose = true))
        assertEquals(SymbolInsertion("{}", 1), symbolInsertion("{", autoClose = true))
        assertEquals(SymbolInsertion("\"\"", 1), symbolInsertion("\"", autoClose = true))
        assertEquals(SymbolInsertion("''", 1), symbolInsertion("'", autoClose = true))
        assertEquals(SymbolInsertion("(", 1), symbolInsertion("(", autoClose = false))
        assertEquals(SymbolInsertion(")", 1), symbolInsertion(")", autoClose = true))
        assertEquals(SymbolInsertion("    ", 4), symbolInsertion("    ", autoClose = true))
    }
}
