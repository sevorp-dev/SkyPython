package dev.skyloft.skypython.runtime

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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
        s.type(SIZE_COMMAND)
        mark = s.await(Regex("(?m)^24 80$"), mark)
        Pty.setWindowSize(s.fd, 30, 100, 0, 0)
        s.type(SIZE_COMMAND)
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

    /**
     * Android 8–13 kill an app process with SIGSYS for a system call outside the app seccomp
     * allow-list, which predates close_range(2). This device may allow it, so the test runs the
     * real libskypty.so (through a minimal stand-in JNIEnv) in a Python process that installs a
     * stricter filter trapping close_range, and checks the child still reaches exec.
     */
    @Test
    fun childStartsUnderASeccompPolicyWithoutCloseRange(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lib = File(context.applicationInfo.nativeLibraryDir, "libskypty.so")
        assertTrue("$lib", lib.isFile)
        val script = File(cwd, "seccomp_pty.py").apply { writeText(SECCOMP_SCRIPT.replace("@LIB@", lib.path)) }
        val events = withTimeout(TIMEOUT_MS) {
            PythonProcess(paths).start(script).toList()
        }
        val out = events.filterIsInstance<ConsoleEvent.Stdout>().joinToString("") { it.text }
        val err = events.filterIsInstance<ConsoleEvent.Stderr>().joinToString("") { it.text }
        assumeFalse("seccomp unavailable: $out", out.contains("SKIP"))
        assertTrue("stdout: $out\nstderr: $err", out.contains("child output: 'alive"))
        assertTrue("stdout: $out\nstderr: $err", out.contains("child status: exit 0"))
    }

    /** Descriptors the app has open (binder, files, sockets) are not passed to the program. */
    @Test
    fun childInheritsNoDescriptorsBeyondStdio() {
        // dup() clears FD_CLOEXEC: only the child's own closing keeps this one out.
        val stream = File(cwd, "leaked-descriptor").apply { writeText("x") }.inputStream()
        val dup = Os.dup(stream.fd)
        try {
            val s = shell()
            val mark = s.text().length
            s.type("echo N=\$(ls -l /proc/\$\$/fd | grep -c leaked-descriptor)\r")
            val n = Regex("(?m)^N=(\\d+)$")
            s.await(n, mark)
            assertEquals("0", n.find(s.text(), mark)!!.groupValues[1])
        } finally {
            Os.close(dup)
            stream.close()
        }
    }

    @Test(expected = IOException::class)
    fun invalidArgumentsFailWithAnIOException() {
        Pty.createSubprocess("/system/bin/sh", "/", arrayOf(), arrayOf(), IntArray(0), 24, 80, 0, 0)
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        val SHELL_PROMPT = Regex("[$#] $")
        val PY_PROMPT = Regex(">>> $")

        /** Prints "rows columns" as the program sees them (Android 7 has no stty). */
        const val SIZE_COMMAND = "python -c 'import os; print(*os.get_terminal_size()[::-1])'\r"

        /**
         * Loads libskypty.so (@LIB@), traps close_range (436 on every ABI) with SIGSYS as the
         * app seccomp policy of Android 8–13 does, then starts `sh -c 'echo alive'` through the
         * JNI entry point with a stand-in JNIEnv implementing only the calls pty.c makes.
         */
        val SECCOMP_SCRIPT = """
            import ctypes, os, sys

            lib = ctypes.CDLL("@LIB@")
            libc = ctypes.CDLL(None, use_errno=True)

            class SockFilter(ctypes.Structure):
                _fields_ = [("code", ctypes.c_ushort), ("jt", ctypes.c_ubyte), ("jf", ctypes.c_ubyte), ("k", ctypes.c_uint32)]

            class SockFprog(ctypes.Structure):
                _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.POINTER(SockFilter))]

            prog = (SockFilter * 4)(
                SockFilter(0x20, 0, 0, 0),           # ld [nr]
                SockFilter(0x15, 0, 1, 436),         # jeq close_range
                SockFilter(0x06, 0, 0, 0x00030000),  # ret TRAP: SIGSYS
                SockFilter(0x06, 0, 0, 0x7FFF0000),  # ret ALLOW
            )
            fprog = SockFprog(4, prog)
            libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong]
            if libc.prctl(38, 1, None, 0, 0) != 0 or libc.prctl(22, 2, ctypes.addressof(fprog), 0, 0) != 0:
                print("SKIP prctl errno", ctypes.get_errno())
                sys.exit(0)

            P = ctypes.c_void_p
            objects, keep, thrown = {}, [], []
            table = (P * 240)()

            def ref(o):
                objects[len(objects) + 1] = o
                return len(objects)

            def jni(index, restype, *argtypes):
                def register(f):
                    cb = ctypes.CFUNCTYPE(restype, P, *argtypes)(f)
                    keep.append(cb)
                    table[index] = ctypes.cast(cb, P).value
                    return f
                return register

            @jni(6, P, ctypes.c_char_p)
            def find_class(env, name):
                return ref(name)

            @jni(14, ctypes.c_int, P, ctypes.c_char_p)
            def throw_new(env, cls, msg):
                thrown.append(msg.decode())
                return 0

            @jni(23, None, P)
            def delete_local_ref(env, o):
                pass

            @jni(169, P, P, P)
            def get_string_utf_chars(env, s, is_copy):
                buf = ctypes.create_string_buffer(objects[s].encode())
                keep.append(buf)
                return ctypes.addressof(buf)

            @jni(170, None, P, P)
            def release_string_utf_chars(env, s, chars):
                pass

            @jni(171, ctypes.c_int, P)
            def get_array_length(env, a):
                return len(objects[a])

            @jni(173, P, P, ctypes.c_int)
            def get_object_array_element(env, a, i):
                return ref(objects[a][i])

            @jni(211, None, P, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int))
            def set_int_array_region(env, a, start, n, buf):
                for i in range(n):
                    objects[a][start + i] = buf[i]

            @jni(228, ctypes.c_ubyte)
            def exception_check(env):
                return 1 if thrown else 0

            table_ptr = P(ctypes.addressof(table))
            env = ctypes.addressof(table_ptr)
            create = lib.Java_dev_skyloft_skypython_runtime_Pty_createSubprocess
            create.restype = ctypes.c_int
            create.argtypes = [P] * 7 + [ctypes.c_int] * 4
            pid_out = [0]
            fd = create(env, ref("Pty"), ref("/system/bin/sh"), ref("/"), ref(["sh", "-c", "echo alive"]),
                        ref(["PATH=/system/bin"]), ref(pid_out), 24, 80, 0, 0)
            if thrown:
                print("createSubprocess threw", thrown)
                sys.exit(1)
            data = b""
            while True:
                try:
                    chunk = os.read(fd, 4096)
                except OSError:
                    break
                if not chunk:
                    break
                data += chunk
            _, status = os.waitpid(pid_out[0], 0)
            print("child output:", repr(data.decode(errors="replace")))
            if os.WIFSIGNALED(status):
                print("child status: signal", os.WTERMSIG(status))
            else:
                print("child status: exit", os.WEXITSTATUS(status))
        """.trimIndent() + "\n"

        private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>78]")

        /** Output as a plain-text log: escapes removed, CRLF → LF. */
        fun stripAnsi(s: String): String = ANSI.replace(s, "").replace("\r", "")
    }
}
