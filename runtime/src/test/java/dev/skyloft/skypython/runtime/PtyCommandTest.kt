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
        // argv[0] is the program name the process sees.
        assertEquals(listOf("python3", "-i", "-q"), cmd.argv)
        assertEquals(projects, cmd.cwd)
    }

    @Test
    fun shellRunsInteractiveSystemSh() {
        val cmd = PtyCommand.of(paths, RunCommand.Shell(projects), appEnv)
        assertEquals("/system/bin/sh", cmd.executable)
        assertEquals(listOf("sh", "-i"), cmd.argv)
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

    @Test(expected = IllegalArgumentException::class)
    fun scriptsDoNotRunInAPty() {
        PtyCommand.of(paths, RunCommand.Script(File(projects, "a.py")), appEnv)
    }
}
