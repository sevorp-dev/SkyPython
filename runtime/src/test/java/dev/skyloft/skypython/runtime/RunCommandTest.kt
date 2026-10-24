package dev.skyloft.skypython.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RunCommandTest {

    // Expected values are taken from File.path so the test also passes on a Windows JVM.
    private val files = File("/data/user/0/dev.skyloft.skypython/files")
    private val nativeLibDir = File("/data/app/~~abc/dev.skyloft.skypython-1/lib/x86_64")
    private val homeDir = File(files, "home")
    private val launcher = File(nativeLibDir, "libpylauncher.so")
    private val paths = PythonPaths(
        home = File(files, "python"),
        nativeLibDir = nativeLibDir,
        launcher = launcher,
        homeDir = homeDir,
        tmpDir = File("/data/user/0/dev.skyloft.skypython/cache"),
    )
    private val projects = File(files, "projects")

    @Test
    fun binDirDefaultsToHomeBin() {
        assertEquals(File(homeDir, "bin"), paths.binDir)
    }

    @Test
    fun scriptCommandIsTheExistingScriptCommand() {
        val script = File(projects, "my scripts/hello world.py")
        val cmd = RunCommand.Script(script)
        assertEquals(PythonEnvironment.command(paths, script), PythonEnvironment.command(paths, cmd))
        assertEquals(script.absoluteFile.parentFile, cmd.workingDir)
    }

    @Test
    fun interpreterRunsPythonInteractiveQuietInCwd() {
        val cmd = RunCommand.Interpreter(projects)
        assertEquals(listOf(launcher.path, "-i", "-q"), PythonEnvironment.command(paths, cmd))
        assertEquals(projects, cmd.workingDir)
    }

    @Test
    fun shellRunsInteractiveSystemShInCwd() {
        val cmd = RunCommand.Shell(projects)
        assertEquals(listOf("/system/bin/sh", "-i"), PythonEnvironment.command(paths, cmd))
        assertEquals(projects, cmd.workingDir)
    }

    @Test
    fun shellEnvironmentIsPythonEnvironmentPlusPath() {
        val env = PythonEnvironment.shellEnvironment(paths)
        assertEquals(paths.binDir.path + ":/system/bin", env["PATH"])
        assertEquals(PythonEnvironment.build(paths), env - "PATH")
    }

    @Test
    fun environmentPicksTheShellEnvironmentOnlyForTheShell() {
        assertEquals(
            PythonEnvironment.shellEnvironment(paths),
            PythonEnvironment.environment(paths, RunCommand.Shell(projects)),
        )
        assertEquals(
            PythonEnvironment.build(paths),
            PythonEnvironment.environment(paths, RunCommand.Interpreter(projects)),
        )
        assertEquals(
            PythonEnvironment.build(paths),
            PythonEnvironment.environment(paths, RunCommand.Script(File(projects, "a.py"))),
        )
    }
}
