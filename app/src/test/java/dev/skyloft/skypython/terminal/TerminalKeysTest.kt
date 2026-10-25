package dev.skyloft.skypython.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeysTest {

    private fun seq(key: ExtraKey, mods: Modifiers = Modifiers(), cursorApp: Boolean = false) =
        TerminalKeys.sequence(key, mods, cursorApp)

    @Test
    fun theRowHasTheMobileTerminalKeysInOrder() {
        assertEquals(
            listOf("ESC", "TAB", "CTRL", "ALT", "-", "/", "|", "←", "↑", "↓", "→"),
            ExtraKey.ROW.map { it.label },
        )
    }

    @Test
    fun plainKeys() {
        assertEquals("\u001b", seq(ExtraKey.Esc))
        assertEquals("\t", seq(ExtraKey.Tab))
        assertEquals("-", seq(ExtraKey.Dash))
        assertEquals("/", seq(ExtraKey.Slash))
        assertEquals("|", seq(ExtraKey.Pipe))
    }

    @Test
    fun arrowsFollowTheCursorKeysMode() {
        assertEquals("\u001b[A", seq(ExtraKey.Up))
        assertEquals("\u001b[B", seq(ExtraKey.Down))
        assertEquals("\u001b[C", seq(ExtraKey.Right))
        assertEquals("\u001b[D", seq(ExtraKey.Left))
        // Full-screen programs (less, vim, Python's REPL) switch to application mode.
        assertEquals("\u001bOA", seq(ExtraKey.Up, cursorApp = true))
        assertEquals("\u001bOD", seq(ExtraKey.Left, cursorApp = true))
    }

    @Test
    fun modifiedArrowsUseXtermParameters() {
        assertEquals("\u001b[1;5A", seq(ExtraKey.Up, Modifiers(ctrl = true)))
        assertEquals("\u001b[1;3D", seq(ExtraKey.Left, Modifiers(alt = true)))
    }

    @Test
    fun altPrefixesEscape() {
        assertEquals("\u001b-", seq(ExtraKey.Dash, Modifiers(alt = true)))
        assertEquals("\u001b\u001b", seq(ExtraKey.Esc, Modifiers(alt = true)))
    }

    @Test
    fun ctrlSlashIsUnitSeparator() {
        assertEquals("\u001f", seq(ExtraKey.Slash, Modifiers(ctrl = true)))
    }

    @Test
    fun modifierKeysThemselvesSendNothing() {
        assertNull(seq(ExtraKey.Ctrl))
        assertNull(seq(ExtraKey.Alt))
    }

    @Test
    fun controlCodesOfLetters() {
        assertEquals(3, TerminalKeys.controlCode('c'.code)) // Ctrl+C: SIGINT via the line discipline
        assertEquals(3, TerminalKeys.controlCode('C'.code))
        assertEquals(4, TerminalKeys.controlCode('d'.code)) // Ctrl+D: end of input
        assertEquals(26, TerminalKeys.controlCode('z'.code))
        assertEquals(27, TerminalKeys.controlCode('['.code))
        assertEquals(0, TerminalKeys.controlCode(' '.code))
        assertEquals('-'.code, TerminalKeys.controlCode('-'.code)) // no control form: unchanged
        assertEquals("\u0003", TerminalKeys.CTRL_C)
    }

    @Test
    fun modifiersToggleAndAreUsedOnce() {
        var m = Modifiers()
        m = m.toggle(ExtraKey.Ctrl)
        assertTrue(m.ctrl)
        assertFalse(m.alt)
        m = m.toggle(ExtraKey.Ctrl)
        assertFalse("a second tap releases it", m.ctrl)

        m = Modifiers().toggle(ExtraKey.Ctrl).toggle(ExtraKey.Alt)
        assertEquals(Modifiers(ctrl = true, alt = true), m)
        // After one key both are released (one-shot, like a phone's Shift).
        assertEquals(Modifiers(), m.afterKey())
    }

    @Test
    fun toggleIgnoresOrdinaryKeys() {
        assertEquals(Modifiers(), Modifiers().toggle(ExtraKey.Tab))
    }
}
