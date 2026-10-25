package dev.skyloft.skypython.terminal

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import dev.skyloft.skypython.runtime.PythonInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The terminal screen's model end to end: [TerminalViewModel] → emulator → PTY → program, without
 * the view (the test plays the view's part by giving the session its size).
 */
@RunWith(AndroidJUnit4::class)
class TerminalSessionDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val vms = mutableListOf<TerminalViewModel>()

    @Before
    fun setUp() {
        File(app.filesDir, "projects").mkdirs()
    }

    @After
    fun tearDown() {
        onMain { vms.forEach { it.kill() } }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Opens [kind] and lays it out at 80×24, as the screen would. */
    private fun open(kind: TerminalKind): TerminalViewModel {
        val installer = PythonInstaller(app)
        val vm = onMain {
            TerminalViewModel(kind, File(app.filesDir, "projects"), app, installer::ensureInstalled).also { it.open() }
        }
        vms += vm
        val session = waitFor("a session") { vm.state.value.session }
        onMain { session.updateSize(80, 24, 10, 20) }
        return vm
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = TIMEOUT_MS, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            onMain(probe)?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private val TerminalViewModel.session: TerminalSession get() = state.value.session!!

    /** Everything on screen and in the scrollback, one line per row (main thread only). */
    private fun TerminalViewModel.screen(): String = session.emulator.screen.transcriptText

    private fun TerminalViewModel.awaitScreen(regex: Regex): String =
        waitFor("/$regex/") { screen().takeIf { regex.containsMatchIn(it) } }

    private fun TerminalViewModel.type(text: String) = onMain {
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    @Test
    fun interpreterEvaluatesAnExpression() {
        val vm = open(TerminalKind.Interpreter)
        vm.awaitScreen(PROMPT)
        vm.type("1+1\r")
        vm.awaitScreen(Regex("(?m)^2\\s*$"))
    }

    @Test
    fun interruptStopsALoopAndTheInterpreterGoesOn() {
        val vm = open(TerminalKind.Interpreter)
        vm.awaitScreen(PROMPT)
        vm.type("while True: pass\r\r")
        Thread.sleep(500)
        onMain { vm.interrupt() } // the top bar's ^C
        vm.awaitScreen(Regex("KeyboardInterrupt"))
        vm.type("6*7\r")
        vm.awaitScreen(Regex("(?m)^42\\s*$"))
        assertEquals(true, onMain { vm.state.value.running })
    }

    @Test
    fun ctrlDEndsTheInterpreter() {
        val vm = open(TerminalKind.Interpreter)
        vm.awaitScreen(PROMPT)
        vm.type("\u0004")
        waitFor("exit") { vm.state.value.takeIf { !it.running } }
        assertFalse(onMain { vm.session.isRunning })
        assertEquals(0, onMain { vm.session.exitStatus })
    }

    @Test
    fun shellRunsOnARealTerminal() {
        val vm = open(TerminalKind.Shell)
        vm.awaitScreen(Regex("[$#] *$"))
        vm.type("echo $((6*7)); tty\r")
        vm.awaitScreen(Regex("(?m)^42\\s*$"))
        vm.awaitScreen(Regex("(?m)^/dev/pts/\\d+\\s*$"))
    }

    @Test
    fun keyRowUpArrowRecallsTheLastCommand() {
        val vm = open(TerminalKind.Shell)
        vm.awaitScreen(Regex("[$#] *$"))
        vm.type("echo sky$((1+1))\r")
        vm.awaitScreen(Regex("(?m)^sky2\\s*$"))
        onMain { vm.onExtraKey(ExtraKey.Up) }
        vm.type("\r")
        waitFor("the command twice") {
            vm.screen().lines().count { it.trim() == "sky2" }.takeIf { it >= 2 }
        }
    }

    @Test
    fun keyRowCtrlIsUsedByTheNextKeyOnly() {
        val vm = open(TerminalKind.Interpreter)
        onMain { vm.onExtraKey(ExtraKey.Ctrl) }
        assertEquals(true, onMain { vm.state.value.modifiers.ctrl })
        assertEquals(true, onMain { vm.consumeCtrl() })
        assertEquals(false, onMain { vm.consumeCtrl() })
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        val PROMPT = Regex(">>> *$", RegexOption.MULTILINE)
    }
}
