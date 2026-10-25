package dev.skyloft.skypython.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalBodyTest {

    private val script = File("/p/a.py")

    @Test
    fun freshInterpreterAndShellStartThemselves() {
        // A screen restored after process death gets a view model that never saw a click.
        assertTrue(TerminalBody.startsWhenShown(TerminalState(), TerminalKind.Interpreter, starting = false))
        assertTrue(TerminalBody.startsWhenShown(TerminalState(), TerminalKind.Shell, starting = false))
    }

    @Test
    fun nothingStartsTwiceOrOverAnError() {
        assertFalse(TerminalBody.startsWhenShown(TerminalState(), TerminalKind.Shell, starting = true))
        assertFalse(TerminalBody.startsWhenShown(TerminalState(installError = "x"), TerminalKind.Shell, starting = false))
        assertFalse(TerminalBody.startsWhenShown(TerminalState(startError = "x"), TerminalKind.Shell, starting = false))
    }

    @Test
    fun runNeverStartsByItself() {
        // Coming back to a finished script must not run it again.
        assertFalse(TerminalBody.startsWhenShown(TerminalState(script = script), TerminalKind.Run, starting = false))
    }

    @Test
    fun installingOnlyWhileInstalling() {
        assertEquals(TerminalBody.Installing, TerminalBody.of(TerminalState(installing = true), TerminalKind.Shell))
        assertEquals(TerminalBody.Starting, TerminalBody.of(TerminalState(), TerminalKind.Shell))
        assertEquals(TerminalBody.Starting, TerminalBody.of(TerminalState(script = script), TerminalKind.Run))
    }

    @Test
    fun runWithoutAScriptHasNothingToShow() {
        assertEquals(TerminalBody.NoScript, TerminalBody.of(TerminalState(), TerminalKind.Run))
    }

    @Test
    fun errorsComeFirst() {
        assertEquals(TerminalBody.InstallError, TerminalBody.of(TerminalState(installError = "x", installing = true), TerminalKind.Run))
        assertEquals(TerminalBody.StartError, TerminalBody.of(TerminalState(startError = "x"), TerminalKind.Shell))
    }
}
