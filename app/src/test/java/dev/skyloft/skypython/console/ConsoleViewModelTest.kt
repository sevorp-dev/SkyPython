package dev.skyloft.skypython.console

import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.RunCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleViewModelTest {

    /** A script process driven by the test through [events]. */
    private class FakeProcess : ScriptProcess {
        val events = Channel<ConsoleEvent>(Channel.UNLIMITED)
        var started = false
        var command: RunCommand? = null
        @Volatile var running = false
        val written = mutableListOf<String>()

        override val isRunning get() = running

        override fun start(command: RunCommand): Flow<ConsoleEvent> = flow {
            this@FakeProcess.command = command
            started = true
            running = true
            try {
                for (e in events) {
                    if (e is ConsoleEvent.Exited) running = false
                    emit(e)
                    if (e is ConsoleEvent.Exited) break
                }
            } finally {
                running = false
            }
        }

        override fun write(text: String) {
            written += text
        }

        override fun stop() {
            events.trySend(ConsoleEvent.Exited(-15))
        }

        var interrupts = 0

        override fun interrupt() {
            interrupts++
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val paths = File("/py").let { PythonPaths(it, it, it, it, it) }
    private val installGate = CompletableDeferred<Result<PythonPaths>>()
    private val processes = mutableListOf<FakeProcess>()
    private val script = File("/p/hello.py")
    private val projects = File("/p")
    private lateinit var vm: ConsoleViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = ConsoleViewModel(
            projectsRoot = projects,
            install = { installGate.await() },
            newProcess = { FakeProcess().also { processes += it } },
            work = dispatcher,
            tickMillis = 50,
        )
    }

    @After
    fun tearDown() {
        processes.forEach { it.events.trySend(ConsoleEvent.Exited(0)) }
        Dispatchers.resetMain()
    }

    /** Ends every script before returning: a live ticker would keep runTest from going idle. */
    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher, timeout = 10.seconds) {
        try {
            body()
        } finally {
            // Also after a failed assertion: otherwise the ticker spins virtual time forever.
            processes.forEach { it.events.trySend(ConsoleEvent.Exited(0)) }
            runCurrent()
        }
    }

    private fun ConsoleLine.text() = pieces.joinToString("") { it.text }
    private val texts get() = vm.state.value.lines.map { it.text() }

    @Test
    fun stopWhilePreparingPythonCancelsTheRun() = test {
        vm.run(script)
        runCurrent()
        assertTrue(vm.state.value.installing)
        assertTrue(vm.state.value.running)

        vm.stop()
        runCurrent()
        assertFalse(vm.state.value.running)
        assertFalse(vm.state.value.installing)
        assertEquals("[Cancelled]", texts.last())

        installGate.complete(Result.success(paths))
        runCurrent()
        assertTrue("script must not start after Stop", processes.none { it.started })
        assertFalse(vm.state.value.running)
    }

    @Test
    fun inputIsAcceptedOnlyWhileTheProcessRuns() = test {
        vm.run(script)
        runCurrent()
        assertTrue(vm.state.value.running)
        assertFalse("no input while preparing Python", vm.state.value.acceptsInput)

        installGate.complete(Result.success(paths))
        runCurrent()
        advanceTimeBy(60)
        runCurrent()
        assertTrue(vm.state.value.acceptsInput)

        vm.send("Sky")
        runCurrent()
        assertEquals(listOf("Sky\n"), processes.single().written)

        processes.single().events.send(ConsoleEvent.Exited(0))
        runCurrent()
        assertFalse(vm.state.value.acceptsInput)
        assertFalse(vm.state.value.running)
    }

    @Test
    fun requestRunWhileIdleStartsRightAway() = test {
        installGate.complete(Result.success(paths))
        assertTrue(vm.requestRun(script))
        runCurrent()
        assertTrue(vm.state.value.running)
        assertEquals(null, vm.state.value.pending)
        assertTrue(processes.single().started)
    }

    @Test
    fun requestRunWhileRunningAsksBeforeKillingTheScript() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()
        val other = File("/p/other.py")

        assertFalse(vm.requestRun(other))
        runCurrent()
        assertEquals(RunTarget.Script(other), vm.state.value.pending)
        assertTrue("running script left alone", processes.single().running)

        vm.confirmPendingRun()
        runCurrent()
        assertEquals(null, vm.state.value.pending)
        assertEquals(2, processes.size)
        assertFalse(processes[0].running)
        assertTrue(processes[1].started)
        assertEquals("other.py", vm.state.value.title)
    }

    @Test
    fun dismissingTheRunRequestKeepsTheScriptRunning() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()
        vm.requestRun(File("/p/other.py"))
        vm.dismissPendingRun()
        runCurrent()
        assertEquals(null, vm.state.value.pending)
        assertEquals(1, processes.size)
        assertTrue(processes.single().running)
        assertEquals("hello.py", vm.state.value.title)
    }

    @Test
    fun exitFooterAddsNoBlankLineAfterANewline() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()
        processes.single().events.send(ConsoleEvent.Stdout("hi\n"))
        processes.single().events.send(ConsoleEvent.Exited(0))
        runCurrent()
        assertEquals(listOf("hi", "[Program finished, exit code 0]"), texts)
    }

    @Test
    fun exitFooterStartsOnItsOwnLineAfterAPartialLine() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()
        processes.single().events.send(ConsoleEvent.Stdout("hi"))
        processes.single().events.send(ConsoleEvent.Exited(3))
        runCurrent()
        assertEquals(listOf("hi", "[Program finished, exit code 3]"), texts)
    }

    @Test
    fun stopWhileScriptRunsStopsTheProcess() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()
        assertTrue(processes.single().started)

        vm.stop()
        runCurrent()
        assertFalse(vm.state.value.running)
        assertEquals("[Program finished, exit code -15]", texts.last())
    }

    @Test
    fun interruptSendsCtrlCToTheRunningScriptWithoutStoppingIt() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()

        vm.interrupt()
        runCurrent()
        assertEquals(1, processes.single().interrupts)
        // The script decides: KeyboardInterrupt may be caught, so the run goes on until it exits.
        assertTrue(vm.state.value.running)
        processes.single().events.send(ConsoleEvent.Stderr("KeyboardInterrupt\n"))
        processes.single().events.send(ConsoleEvent.Exited(130))
        runCurrent()
        assertFalse(vm.state.value.running)
        assertEquals("[Program finished, exit code 130]", texts.last())
    }

    @Test
    fun interruptWithNothingRunningDoesNothing() = test {
        vm.interrupt()
        runCurrent()
        assertTrue(processes.isEmpty())
        assertFalse(vm.state.value.running)
    }

    @Test
    fun scriptRunTitleIsTheScriptName() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()
        assertEquals("hello.py", vm.state.value.title)
        assertEquals(RunCommand.Script(script), processes.single().command)
    }

    @Test
    fun startInterpreterRunsTheReplInTheProjectsRoot() = test {
        installGate.complete(Result.success(paths))
        assertTrue(vm.startInterpreter())
        runCurrent()
        assertEquals("Interpreter", vm.state.value.title)
        assertTrue(vm.state.value.running)
        assertEquals(RunCommand.Interpreter(projects), processes.single().command)
    }

    @Test
    fun startTerminalRunsTheShellInTheProjectsRoot() = test {
        installGate.complete(Result.success(paths))
        assertTrue(vm.startTerminal())
        runCurrent()
        assertEquals("Terminal", vm.state.value.title)
        assertEquals(RunCommand.Shell(projects), processes.single().command)
    }

    @Test
    fun startInterpreterWhileAScriptRunsAsksFirst() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()

        assertFalse(vm.startInterpreter())
        runCurrent()
        assertEquals(RunTarget.Interpreter, vm.state.value.pending)
        assertEquals("Interpreter", vm.state.value.pending?.title)
        assertTrue("running script left alone", processes.single().running)

        vm.confirmPendingRun()
        runCurrent()
        assertEquals(null, vm.state.value.pending)
        assertFalse(processes[0].running)
        assertEquals(RunCommand.Interpreter(projects), processes[1].command)
        assertEquals("Interpreter", vm.state.value.title)
    }

    @Test
    fun restartRerunsTheSameMode() = test {
        installGate.complete(Result.success(paths))
        vm.startTerminal()
        runCurrent()
        processes.single().events.send(ConsoleEvent.Exited(0))
        runCurrent()

        vm.restart()
        runCurrent()
        assertEquals(2, processes.size)
        assertEquals(RunCommand.Shell(projects), processes[1].command)
        assertEquals("Terminal", vm.state.value.title)
    }

    @Test
    fun interpreterPromptsOnStderrAreNotErrors() = test {
        installGate.complete(Result.success(paths))
        vm.startInterpreter()
        runCurrent()
        val p = processes.single()
        p.events.send(ConsoleEvent.Stderr(">>> "))
        p.events.send(ConsoleEvent.Stderr("Traceback\nZeroDivisionError\n>>> "))
        advanceTimeBy(60)
        runCurrent()
        val lines = vm.state.value.lines
        assertEquals(listOf(Segment(">>> ", false), Segment("Traceback", true)), lines[0].pieces)
        assertEquals(listOf(Segment("ZeroDivisionError", true)), lines[1].pieces)
        assertEquals(listOf(Segment(">>> ", false)), lines[2].pieces)
    }

    @Test
    fun replOutputRacingAheadOfItsPromptStillComesBeforeIt() = test {
        // stdout and stderr are separate pipes: the next prompt can be read before the result.
        installGate.complete(Result.success(paths))
        vm.startInterpreter()
        runCurrent()
        val p = processes.single()
        p.events.send(ConsoleEvent.Stderr(">>> "))
        advanceTimeBy(60)
        runCurrent()
        vm.send("1+1")
        runCurrent()
        p.events.send(ConsoleEvent.Stderr(">>> "))
        p.events.send(ConsoleEvent.Stdout("2\n"))
        advanceTimeBy(60)
        runCurrent()
        assertEquals(listOf(">>> 1+1", "2", ">>> "), texts)
    }

    @Test
    fun heldPromptIsShownBeforeTheExitFooter() = test {
        installGate.complete(Result.success(paths))
        vm.startTerminal()
        runCurrent()
        val p = processes.single()
        p.events.send(ConsoleEvent.Stderr("$ "))
        p.events.send(ConsoleEvent.Exited(0))
        runCurrent()
        assertEquals(listOf("$ ", "[Program finished, exit code 0]"), texts)
    }

    @Test
    fun scriptStderrStaysAnErrorEvenIfItLooksLikeAPrompt() = test {
        installGate.complete(Result.success(paths))
        vm.run(script)
        runCurrent()
        processes.single().events.send(ConsoleEvent.Stderr(">>> "))
        advanceTimeBy(60)
        runCurrent()
        assertEquals(listOf(Segment(">>> ", true)), vm.state.value.lines.single().pieces)
    }
}
