package dev.skyloft.skypython.runtime

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** The interpreter and the shell on a real pseudo-terminal ([Pty]), driven like a user would. */
@RunWith(AndroidJUnit4::class)
class PtyDeviceTest {

    private lateinit var paths: PythonPaths
    private lateinit var cwd: File
    private val sessions = mutableListOf<Session>()

    @Before
    fun setUp(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        paths = PythonInstaller(context).ensureInstalled().getOrThrow()
        cwd = File(context.filesDir, "pty test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sessions.forEach { it.kill() }
    }

    /** A program on a PTY; everything it prints is collected (ANSI escapes stripped). */
    private inner class Session(command: RunCommand, rows: Int = 24, cols: Int = 80) {
        val pid: Int
        val fd: Int
        private val pfd: ParcelFileDescriptor
        private val raw = StringBuilder()
        private val out: FileOutputStream
        @Volatile var exitCode: Int? = null

        init {
            val pidOut = IntArray(1)
            fd = Pty.start(PtyCommand.prepare(paths, command), rows, cols, pidOut)
            pid = pidOut[0]
            pfd = ParcelFileDescriptor.adoptFd(fd) // closes the master in kill()
            out = FileOutputStream(pfd.fileDescriptor)
            Thread {
                val input = FileInputStream(pfd.fileDescriptor)
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        synchronized(raw) { raw.append(String(buf, 0, n, Charsets.UTF_8)) }
                    }
                } catch (e: IOException) {
                    // EIO once the child side is closed.
                }
            }.apply { isDaemon = true }.start()
            Thread { exitCode = Pty.waitFor(pid) }.apply { isDaemon = true }.start()
            sessions += this
        }

        fun text(): String = synchronized(raw) { stripAnsi(raw.toString()) }

        fun type(s: String) {
            out.write(s.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        /** Waits until [regex] matches the output after [from]; returns the end of the match. */
        fun await(regex: Regex, from: Int = 0, timeoutMs: Long = TIMEOUT_MS): Int {
            val deadline = System.currentTimeMillis() + timeoutMs
            var exitSeenAt = 0L
            while (System.currentTimeMillis() < deadline) {
                val t = text()
                regex.find(t, minOf(from, t.length))?.let { return it.range.last + 1 }
                if (exitCode != null) {
                    // Output still in flight is given a moment after the exit.
                    if (exitSeenAt == 0L) exitSeenAt = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - exitSeenAt > 1_000) {
                        throw AssertionError("exited ($exitCode) before /$regex/:\n$t")
                    }
                }
                Thread.sleep(20)
            }
            throw AssertionError("no /$regex/ within $timeoutMs ms:\n${text()}")
        }

        fun awaitExit(timeoutMs: Long = TIMEOUT_MS): Int {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (exitCode == null) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("still running:\n${text()}")
                Thread.sleep(20)
            }
            return exitCode!!
        }

        fun kill() {
            if (exitCode == null) Pty.sendSignal(pid, Pty.SIGKILL)
            pfd.close()
        }
    }

    private fun shell() = Session(RunCommand.Shell(cwd)).also { it.await(SHELL_PROMPT) }
    private fun interpreter() = Session(RunCommand.Interpreter(cwd)).also { it.await(PY_PROMPT) }

    @Test
    fun shellEvaluatesArithmetic() {
        val s = shell()
        val mark = s.text().length
        s.type("echo $((6*7))\r")
        // The echoed command line has no "42": only the result does.
        s.await(Regex("(?m)^42$"), mark)
        s.type("exit\r")
        assertEquals(0, s.awaitExit())
    }

    @Test
    fun shellHasARealTerminal() {
        val s = shell()
        val mark = s.text().length
        s.type("tty\r")
        s.await(Regex("(?m)^/dev/pts/\\d+$"), mark)
    }

    @Test
    fun shellFindsPythonAndPip() {
        val s = shell()
        var mark = s.text().length
        s.type("python -c 'print(40+2)'\r")
        mark = s.await(Regex("(?m)^42$"), mark)
        s.type("pip --version\r")
        s.await(Regex("""pip \d+\.\d+.* from """), mark)
    }

    @Test
    fun windowSizeReachesTheProgram() {
        val s = Session(RunCommand.Shell(cwd), rows = 24, cols = 80).also { it.await(SHELL_PROMPT) }
        var mark = s.text().length
        s.type("stty size\r")
        mark = s.await(Regex("(?m)^24 80$"), mark)
        Pty.setWindowSize(s.fd, 30, 100, 0, 0)
        s.type("stty size\r")
        s.await(Regex("(?m)^30 100$"), mark)
    }

    @Test
    fun interpreterEvaluatesAnExpression() {
        val s = interpreter()
        val mark = s.text().length
        s.type("1+1\r")
        s.await(Regex("(?m)^2$"), mark)
    }

    @Test
    fun ctrlCInterruptsALoopAndTheInterpreterKeepsRunning() {
        val s = interpreter()
        var mark = s.text().length
        s.type("while True: pass\r")
        s.type("\r") // ends the compound statement
        Thread.sleep(500) // let it reach the loop
        s.type("\u0003") // Ctrl+C: the line discipline sends SIGINT to the foreground group
        mark = s.await(Regex("KeyboardInterrupt"), mark)
        mark = s.await(PY_PROMPT, mark)
        s.type("6*7\r")
        s.await(Regex("(?m)^42$"), mark)
        s.type("\u0004") // Ctrl+D at an empty prompt exits
        assertEquals(0, s.awaitExit())
    }

    @Test
    fun sigintToTheForegroundGroupInterruptsToo() {
        val s = interpreter()
        var mark = s.text().length
        s.type("while True: pass\r\r")
        Thread.sleep(500)
        val group = Pty.foregroundProcessGroup(s.fd)
        assertTrue("foreground group $group", group > 0)
        assertEquals(0, Pty.sendSignal(-group, Pty.SIGINT))
        mark = s.await(Regex("KeyboardInterrupt"), mark)
        s.await(PY_PROMPT, mark)
    }

    @Test
    fun interpreterRunsInTheGivenDirectory() {
        val s = interpreter()
        val mark = s.text().length
        s.type("import os; print(os.getcwd())\r")
        s.await(Regex("(?m)^/.*${Regex.escape(cwd.name)}$"), mark)
    }

    @Test
    fun interpreterKnowsItsExecutable() {
        val s = interpreter()
        val mark = s.text().length
        s.type("import sys; print(sys.executable)\r")
        s.await(Regex("(?m)^/.*libpylauncher\\.so$"), mark)
    }

    @Test(expected = IOException::class)
    fun invalidArgumentsFailWithAnIOException() {
        Pty.createSubprocess("/system/bin/sh", "/", arrayOf(), arrayOf(), IntArray(0), 24, 80, 0, 0)
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        val SHELL_PROMPT = Regex("[$#] $")
        val PY_PROMPT = Regex(">>> $")

        private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>78]")

        /** Output as a plain-text log: escapes removed, CRLF → LF. */
        fun stripAnsi(s: String): String = ANSI.replace(s, "").replace("\r", "")
    }
}
