package dev.skyloft.skypython.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.DebugSocket
import dev.skyloft.skypython.runtime.DebuggerInstaller
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.runtime.PythonProcess
import dev.skyloft.skypython.runtime.RunCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole debugger on a device: the bundled Python runs a script under skydbg.py
 * ([RunCommand.Debug]) and [DebugSession] drives it over the Unix socket, as the app does.
 */
@RunWith(AndroidJUnit4::class)
class DebuggerDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(context.filesDir, "debugger-test").canonicalFile.apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun DebugSession.await(what: String, predicate: (DebugState) -> Boolean): DebugState =
        try {
            withTimeout(TIMEOUT_MS) { state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what; state: ${state.value}", e)
        }

    @Test
    fun breakpointVariablesStepAndContinueToTheEnd(): Unit = runBlocking {
        val script = File(dir, "main.py").apply {
            writeText(
                """
                def area(w, h):
                    result = w * h
                    return result

                sizes = [area(2, 3), area(4, 5)]
                name = input()
                print("total", sum(sizes), name)
                """.trimIndent() + "\n",
            )
        }
        val paths = PythonInstaller(context).ensureInstalled().getOrThrow()
        val driver = DebuggerInstaller(context).ensureInstalled().getOrThrow()
        val channel = DebuggerInstaller(context).newChannel()
        val process = PythonProcess(paths)
        val events = async(Dispatchers.IO) { process.start(RunCommand.Debug(script, driver, channel)).toList() }

        val socket = withContext(Dispatchers.IO) { DebugSocket.connect(channel, TIMEOUT_MS) { !events.isCompleted } }
        val session = DebugSession(scope)
        session.begin(script.name)
        session.attach(
            socket,
            breakpoints = mapOf(script.path to listOf(Breakpoint(2))),
            watches = listOf("w * h", "missing"),
            userRoots = listOf(dir.path),
        )

        // Hit the breakpoint in area(2, 3) and read its variables and watches.
        var st = session.await("breakpoint") { it.phase == DebugPhase.Paused && it.locals != null && it.watches.all { w -> w.result != null } }
        assertEquals(StopReason.BREAKPOINT, st.reason)
        assertEquals(listOf("area", "<module>"), st.frames.map { it.name })
        assertEquals(script.path, st.frames[0].file)
        assertEquals(2, st.frames[0].line)
        assertEquals("2", st.locals!!.variables.first { it.name == "w" }.value)
        assertEquals("6", (st.watches[0].result as EvalResult.Value).variable.value)
        assertTrue(st.watches[1].result is EvalResult.Error)

        // Step over to the return line: result is now set.
        session.resume(Step.Over)
        st = session.await("step") { it.phase == DebugPhase.Paused && it.frames.firstOrNull()?.line == 3 && it.locals != null }
        assertEquals("6", st.locals!!.variables.first { it.name == "result" }.value)

        // Continue: the second call hits the breakpoint again.
        session.resume(Step.Continue)
        st = session.await("second hit") { it.phase == DebugPhase.Paused && it.locals?.variables?.any { v -> v.name == "w" && v.value == "4" } == true }

        // Step out to the module, then expand the list being built... and let it run to the end,
        // answering input() through the program's own stdin.
        session.resume(Step.Out)
        session.await("back in the module") { it.phase == DebugPhase.Paused && it.frames.firstOrNull()?.name == "<module>" }
        session.updateBreakpoints(script.path, emptyList())
        session.resume(Step.Continue)
        session.await("running") { it.phase == DebugPhase.Running }
        process.write("sky\n")

        st = session.await("end") { it.phase == DebugPhase.Ended }
        assertEquals(0, st.exitCode)
        val output = events.await()
        val stdout = output.filterIsInstance<ConsoleEvent.Stdout>().joinToString("") { it.text }
        assertEquals("total 26 sky\n", stdout)
        assertEquals(ConsoleEvent.Exited(0), output.last())
    }

    @Test
    fun uncaughtExceptionStopsThenTheProgramEndsWithItsTraceback(): Unit = runBlocking {
        val script = File(dir, "crash.py").apply { writeText("def f(d):\n    return 1 / d\n\nf(0)\n") }
        val paths = PythonInstaller(context).ensureInstalled().getOrThrow()
        val installer = DebuggerInstaller(context)
        val driver = installer.ensureInstalled().getOrThrow()
        val channel = installer.newChannel()
        val process = PythonProcess(paths)
        val events = async(Dispatchers.IO) { process.start(RunCommand.Debug(script, driver, channel)).toList() }
        val socket = withContext(Dispatchers.IO) { DebugSocket.connect(channel, TIMEOUT_MS) { !events.isCompleted } }
        val session = DebugSession(scope)
        session.begin(script.name)
        session.attach(socket, emptyMap(), emptyList(), listOf(dir.path))

        val st = session.await("exception") { it.phase == DebugPhase.Paused && it.locals != null }
        assertEquals(StopReason.EXCEPTION, st.reason)
        assertEquals("ZeroDivisionError: division by zero", st.exception)
        assertEquals("0", st.locals!!.variables.single { it.name == "d" }.value)
        session.resume(Step.Continue)
        assertEquals(1, session.await("end") { it.phase == DebugPhase.Ended }.exitCode)
        val stderr = events.await().filterIsInstance<ConsoleEvent.Stderr>().joinToString("") { it.text }
        assertTrue(stderr, "ZeroDivisionError" in stderr && "skydbg" !in stderr)
    }

    private companion object {
        /** The first run unpacks Python. */
        const val TIMEOUT_MS = 120_000L
    }
}
