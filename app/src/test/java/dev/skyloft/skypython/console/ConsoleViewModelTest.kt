package dev.skyloft.skypython.console

import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonPaths
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

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleViewModelTest {

    /** A script process driven by the test through [events]. */
    private class FakeProcess : ScriptProcess {
        val events = Channel<ConsoleEvent>(Channel.UNLIMITED)
        var started = false
        @Volatile var running = false
        val written = mutableListOf<String>()

        override val isRunning get() = running

        override fun start(script: File): Flow<ConsoleEvent> = flow {
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
    }

    private val dispatcher = StandardTestDispatcher()
    private val paths = File("/py").let { PythonPaths(it, it, it, it, it) }
    private val installGate = CompletableDeferred<Result<PythonPaths>>()
    private val processes = mutableListOf<FakeProcess>()
    private val script = File("/p/hello.py")
    private lateinit var vm: ConsoleViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = ConsoleViewModel(
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

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

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
}
