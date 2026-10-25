package dev.skyloft.skypython.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PtyCommandTest {

    private val files = File("/data/user/0/dev.skyloft.skypython/files")
    private val nativeLibDir = File("/data/app/~~abc/dev.skyloft.skypython-1/lib/x86_64")
    private val launcher = File(nativeLibDir, "libpylauncher.so")
    private val paths = PythonPaths(
        home = File(files, "python"),
        nativeLibDir = nativeLibDir,
        launcher = launcher,
        homeDir = File(files, "home"),
        tmpDir = File("/data/user/0/dev.skyloft.skypython/cache"),
    )
    private val projects = File(files, "projects")
    private val appEnv = mapOf(
        "ANDROID_DATA" to "/data",
        "PATH" to "/product/bin:/system/bin",
        "HOME" to "/",
    )

    private fun List<String>.asMap(): Map<String, String> =
        associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun interpreterRunsTheLauncherInteractiveQuiet() {
        val cmd = PtyCommand.of(paths, RunCommand.Interpreter(projects), appEnv)
        assertEquals(launcher.path, cmd.executable)
        // argv[0] is the full path: Python computes sys.executable from it.
        assertEquals(listOf(launcher.path, "-i", "-q"), cmd.argv)
        assertEquals(projects, cmd.cwd)
    }

    @Test
    fun shellRunsInteractiveSystemSh() {
        val cmd = PtyCommand.of(paths, RunCommand.Shell(projects), appEnv)
        assertEquals("/system/bin/sh", cmd.executable)
        assertEquals(listOf("/system/bin/sh", "-i"), cmd.argv)
        assertEquals(projects, cmd.cwd)
    }

    @Test
    fun environmentIsTheAppsPlusPythonsWithPythonWinning() {
        val env = PtyCommand.of(paths, RunCommand.Interpreter(projects), appEnv).env.asMap()
        assertEquals("/data", env["ANDROID_DATA"])
        // A PTY child gets exactly this environment (no inheritance): app's vars must be kept.
        assertEquals(paths.homeDir.path, env["HOME"])
        assertEquals("/product/bin:/system/bin", env["PATH"])
        for ((k, v) in PythonEnvironment.build(paths)) assertEquals(k, v, env[k])
    }

    @Test
    fun shellKeepsTheRcFileAndItsPath() {
        val env = PtyCommand.of(paths, RunCommand.Shell(projects), appEnv).env.asMap()
        assertEquals(ShellShims.rcFile(paths).path, env["ENV"])
        assertEquals("/system/bin", env["PATH"])
    }

    @Test
    fun terminalTypeIsAdvertised() {
        for (c in listOf(RunCommand.Interpreter(projects), RunCommand.Shell(projects))) {
            val env = PtyCommand.of(paths, c, appEnv).env.asMap()
            assertEquals("xterm-256color", env["TERM"])
            assertEquals("truecolor", env["COLORTERM"])
        }
    }

    @Test
    fun eachVariableAppearsOnce() {
        val env = PtyCommand.of(paths, RunCommand.Shell(projects), appEnv).env
        val keys = env.map { it.substringBefore('=') }
        assertEquals(keys.distinct(), keys)
        assertTrue(env.all { '=' in it })
    }

    @Test
    fun scriptRunsUnbufferedInItsOwnFolder() {
        val script = File(projects, "demo/a.py")
        val cmd = PtyCommand.of(paths, RunCommand.Script(script), appEnv)
        assertEquals(launcher.path, cmd.executable)
        assertEquals(listOf(launcher.path, "-u", script.path), cmd.argv)
        // The script's folder (absolute; on the host test JVM that may add a drive letter).
        assertEquals(File(projects, "demo").absoluteFile, cmd.cwd)
    }

    @Test
    fun scriptGetsPythonsVariablesAndAColourTerminal() {
        val env = PtyCommand.of(paths, RunCommand.Script(File(projects, "a.py")), appEnv).env.asMap()
        for ((k, v) in PythonEnvironment.build(paths)) assertEquals(k, v, env[k])
        assertEquals("xterm-256color", env["TERM"])
        // Colour output (tracebacks, rich, colorama) must not be switched off.
        assertEquals(null, env["NO_COLOR"])
        assertEquals(null, env["PYTHON_COLORS"])
    }

    @Test
    fun noColorFromTheAppIsNotPassedOn() {
        val env = PtyCommand.of(paths, RunCommand.Script(File(projects, "a.py")), appEnv + ("NO_COLOR" to "1")).env.asMap()
        assertEquals(null, env["NO_COLOR"])
    }
}
