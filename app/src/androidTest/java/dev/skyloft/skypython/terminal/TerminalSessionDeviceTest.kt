package dev.skyloft.skypython.terminal

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import dev.skyloft.skypython.runtime.PythonInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun TerminalViewModel.awaitScreen(regex: Regex, timeoutMs: Long = TIMEOUT_MS): String =
        try {
            waitFor("/$regex/", timeoutMs) { screen().takeIf { regex.containsMatchIn(it) } }
        } catch (e: AssertionError) {
            // Show what the terminal had, so a CI failure says why.
            throw AssertionError("${e.message}; screen ends with: ${onMain { screen() }.takeLast(600)}")
        }

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

    /** How long a Runnable posted to the main thread waits before it runs. */
    private fun mainThreadLatencyMs(): Long {
        val ran = CountDownLatch(1)
        val posted = SystemClock.elapsedRealtime()
        Handler(Looper.getMainLooper()).post { ran.countDown() }
        if (!ran.await(10, TimeUnit.SECONDS)) return 10_000
        return SystemClock.elapsedRealtime() - posted
    }

    /** A program printing as fast as it can must not take the main thread (UI, Stop) for itself. */
    @Test
    fun floodingOutputKeepsTheMainThreadResponsiveAndKillEndsIt() {
        val vm = open(TerminalKind.Interpreter)
        vm.awaitScreen(PROMPT)
        // Output that costs the emulator more than it costs the program (ESC # 8 fills the
        // screen), as plain text does on a slow device: the reader always has more ready.
        // One line: on a slow emulator a second line typed while the first runs can be lost.
        vm.type("import os; line = b'\\x1b#8' * 20 + b'0123456789' * 7 + b'\\r\\n'; chunk = line * 200; exec('while True: os.write(1, chunk)')\r")
        vm.awaitScreen(Regex("(0123456789){7}"), timeoutMs = 120_000)
        var worst = 0L
        val until = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < until) {
            worst = maxOf(worst, mainThreadLatencyMs())
            Thread.sleep(50)
        }
        // Before the fix the main thread was blocked for minutes; slow CI emulators show ~1.3 s hiccups.
        assertTrue("main thread blocked for $worst ms", worst < 3_000)
        val killedAt = SystemClock.elapsedRealtime()
        onMain { vm.kill() }
        // Stop must end it promptly: a few seconds even on slow CI emulators (it used to hang for good).
        waitFor("exit", timeoutMs = 15_000) { vm.state.value.takeIf { !it.running } }
        val stopMs = SystemClock.elapsedRealtime() - killedAt
        assertTrue("stop took $stopMs ms", stopMs < 15_000)
        assertEquals(-9, onMain { vm.session.exitStatus })
    }

    private fun alive(pid: Int): Boolean {
        val stat = File("/proc/$pid/stat")
        if (!stat.exists()) return false
        val text = runCatching { stat.readText() }.getOrNull() ?: return false
        return text.substringAfterLast(')').trim().firstOrNull() != 'Z'
    }

    /** Kill ends the whole session: a background job of the shell goes too. */
    @Test
    fun killEndsBackgroundJobsToo() {
        val vm = open(TerminalKind.Shell)
        vm.awaitScreen(Regex("[$#] *$"))
        vm.type("sleep 1000 & echo bg=$!\r")
        val pid = Regex("(?m)^bg=(\\d+)\\s*$").find(vm.awaitScreen(Regex("(?m)^bg=\\d+\\s*$")))!!.groupValues[1].toInt()
        assertTrue(alive(pid))
        onMain { vm.kill() }
        waitFor("exit") { vm.state.value.takeIf { !it.running } }
        waitFor("the background job to end", timeoutMs = 5_000) { (!alive(pid)).takeIf { it } }
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
