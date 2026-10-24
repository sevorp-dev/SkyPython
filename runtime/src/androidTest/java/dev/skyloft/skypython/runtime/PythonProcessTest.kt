package dev.skyloft.skypython.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class PythonProcessTest {

    private lateinit var paths: PythonPaths
    private lateinit var scriptDir: File

    @Before
    fun setUp(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = PythonInstaller(context).ensureInstalled().getOrThrow()
        scriptDir = File(context.filesDir, "my scripts").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun script(source: String): File =
        File(scriptDir, "test script.py").apply { writeText(source) }

    private fun List<ConsoleEvent>.stdout() =
        filterIsInstance<ConsoleEvent.Stdout>().joinToString("") { it.text }

    private fun List<ConsoleEvent>.stderr() =
        filterIsInstance<ConsoleEvent.Stderr>().joinToString("") { it.text }

    private fun run(source: String): List<ConsoleEvent> = runBlocking {
        withTimeout(TIMEOUT_MS) { PythonProcess(paths).start(script(source)).toList() }
    }

    /** Collects [flow] in the background, recording events as they arrive. */
    private class Collector(flow: Flow<ConsoleEvent>, scope: CoroutineScope) {
        val events: MutableList<ConsoleEvent> = Collections.synchronizedList(mutableListOf())
        val job = scope.async(Dispatchers.IO) { flow.collect { events += it } }
        fun stdout() = synchronized(events) {
            events.filterIsInstance<ConsoleEvent.Stdout>().joinToString("") { it.text }
        }
        suspend fun awaitStdout(text: String) {
            while (!stdout().contains(text)) {
                assertTrue("process ended before \"$text\": $events", job.isActive)
                delay(20)
            }
        }
    }

    @Test
    fun printsAndExitsZero() {
        val events = run("print(2 + 2)")
        assertTrue(events.stdout(), events.stdout().contains("4"))
        assertEquals(ConsoleEvent.Exited(0), events.last())
    }

    @Test
    fun readsStdin(): Unit = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val process = PythonProcess(paths)
            val c = Collector(process.start(script("print(input())")), this)
            while (!process.isRunning) delay(10)
            process.write("hi\n")
            c.job.await()
            assertTrue(c.stdout(), c.stdout().contains("hi"))
            assertEquals(ConsoleEvent.Exited(0), c.events.last())
        }
    }

    @Test
    fun promptWithoutNewlineArrivesBeforeInput(): Unit = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val process = PythonProcess(paths)
            val c = Collector(
                process.start(
                    script("import sys; sys.stdout.write(\"Name: \"); sys.stdout.flush(); input()")
                ),
                this,
            )
            // Nothing has been written to stdin yet: the prompt must already be visible.
            c.awaitStdout("Name: ")
            process.write("x\n")
            c.job.await()
            assertEquals(ConsoleEvent.Exited(0), c.events.last())
        }
    }

    @Test
    fun nonAsciiRoundTrips() {
        val events = run("print(\"Привет\")")
        assertTrue(events.stdout(), events.stdout().contains("Привет"))
        assertEquals(ConsoleEvent.Exited(0), events.last())
    }

    @Test
    fun exceptionGoesToStderrWithExitOne() {
        val events = run("raise ValueError(\"boom\")")
        assertTrue(events.stderr(), events.stderr().contains("ValueError: boom"))
        assertEquals(ConsoleEvent.Exited(1), events.last())
    }

    @Test
    fun stopEndsInfiniteLoopWithinTwoSeconds(): Unit = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val process = PythonProcess(paths)
            val c = Collector(process.start(script("while True: pass")), this)
            while (!process.isRunning) delay(10)
            delay(500) // let the interpreter reach the loop
            val stoppedAt = System.nanoTime()
            process.stop()
            withTimeout(2_000) { c.job.await() }
            val elapsedMs = (System.nanoTime() - stoppedAt) / 1_000_000
            assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
            assertTrue(c.events.toString(), c.events.last() is ConsoleEvent.Exited)
            assertTrue(!process.isRunning)
        }
    }

    @Test
    fun nativeModulesLoad() {
        val events = run("import json, sqlite3, ssl, hashlib; print(\"ok\")")
        assertTrue(events.stdout() + events.stderr(), events.stdout().contains("ok"))
        assertEquals(ConsoleEvent.Exited(0), events.last())
    }

    @Test
    fun exitsEvenIfAChildProcessKeepsOutputOpen(): Unit = runBlocking {
        // The forked child inherits stdout/stderr and sleeps; the script itself ends at once.
        val source = """
            import os, time
            if os.fork() == 0:
                time.sleep(30)
                os._exit(0)
            print("parent done")
        """.trimIndent()
        val startedAt = System.nanoTime()
        val events = withTimeout(TIMEOUT_MS) { PythonProcess(paths).start(script(source)).toList() }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(events.stdout(), events.stdout().contains("parent done"))
        assertEquals(ConsoleEvent.Exited(0), events.last())
        assertTrue("took $elapsedMs ms", elapsedMs < 10_000)
    }

    @Test
    fun extraNativeModulesLoad() {
        val events = run("import ctypes, lzma, bz2, zlib, _decimal; print(\"ok\")")
        assertTrue(events.stdout() + events.stderr(), events.stdout().contains("ok"))
        assertEquals(ConsoleEvent.Exited(0), events.last())
    }

    /** Starts [command], types [input] once it is running, waits for exit; returns all output. */
    private fun interact(command: RunCommand, input: String): List<ConsoleEvent> = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val process = PythonProcess(paths)
            val c = Collector(process.start(command), this)
            while (!process.isRunning) {
                assertTrue("not started: ${c.events}", c.job.isActive)
                delay(10)
            }
            process.write(input)
            c.job.await()
            c.events.toList()
        }
    }

    @Test
    fun interpreterEvaluatesAnExpression() {
        val events = interact(RunCommand.Interpreter(scriptDir), "1+1\nexit()\n")
        assertTrue(events.toString(), events.stdout().contains("2"))
        assertTrue(events.stderr(), events.stderr().contains(">>> "))
        assertEquals(ConsoleEvent.Exited(0), events.last())
    }

    @Test
    fun interpreterRunsInTheGivenDirectory() {
        val events = interact(RunCommand.Interpreter(scriptDir), "import os; print(os.getcwd())\nexit()\n")
        assertTrue(events.toString(), events.stdout().contains(scriptDir.name))
    }

    @Test
    fun terminalRunsShellCommands() {
        val events = interact(RunCommand.Shell(scriptDir), "echo hi\nexit\n")
        assertTrue(events.toString(), events.stdout().contains("hi"))
        assertEquals(ConsoleEvent.Exited(0), events.last())
    }

    @Test
    fun terminalFindsPythonOnPath() {
        val events = interact(RunCommand.Shell(scriptDir), "python -c \"print(40+2)\"\nexit\n")
        assertTrue(events.toString(), events.stdout().contains("42"))
    }

    @Test
    fun terminalPipShimRunsPythonPip() {
        // pip itself may not be installed yet (ensurepip is the Pip screen's job); either way the
        // shim must reach Python: "pip X from ..." or "No module named pip", not "not found".
        val events = interact(RunCommand.Shell(scriptDir), "pip --version\nexit\n")
        val all = events.stdout() + events.stderr()
        assertTrue(all, all.contains("pip ") || all.contains("No module named pip"))
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
