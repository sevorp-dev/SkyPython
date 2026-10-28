package dev.skyloft.skypython.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PythonEnvironmentTest {

    // Expected values are taken from File.path so the test also passes on a Windows JVM.
    private val files = File("/data/user/0/dev.skyloft.skypython/files")
    private val nativeLibDir = File("/data/app/~~abc/dev.skyloft.skypython-1/lib/x86_64")
    private val pythonHome = File(files, "python")
    private val homeDir = File(files, "home")
    private val cacheDir = File("/data/user/0/dev.skyloft.skypython/cache")
    private val launcher = File(nativeLibDir, "libpylauncher.so")
    private val paths = PythonPaths(
        home = pythonHome,
        nativeLibDir = nativeLibDir,
        launcher = launcher,
        homeDir = homeDir,
        tmpDir = cacheDir,
    )

    @Test
    fun buildReturnsExactlyTheProcessEnvironment() {
        assertEquals(
            mapOf(
                "PYTHONHOME" to pythonHome.path,
                "LD_LIBRARY_PATH" to nativeLibDir.path,
                "PYTHONUNBUFFERED" to "1",
                "PYTHONIOENCODING" to "utf-8",
                "HOME" to homeDir.path,
                "TMPDIR" to cacheDir.path,
                "PYTHONUSERBASE" to File(homeDir, ".local").path,
                "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
                "PYTHONPATH" to File(files, "plot").path,
                "MPLBACKEND" to "module://skypython_plot",
                "SKYPYTHON_PLOT_DIR" to File(cacheDir, "plots").path,
            ),
            PythonEnvironment.build(paths),
        )
    }

    @Test
    fun userBaseIsDotLocalInHome() {
        assertEquals(File(homeDir, ".local"), paths.userBase)
    }

    @Test
    fun commandRunsScriptUnbufferedWithLauncher() {
        val script = File(files, "projects/my scripts/hello world.py")
        assertEquals(
            listOf(launcher.path, "-u", script.path),
            PythonEnvironment.command(paths, script),
        )
    }

    @Test
    fun languageServerIsNotIsolatedSoItSeesPythonHomeAndUserPackages() {
        val script = File(files, "langserver/skylang_server.py")
        val cmd = PythonEnvironment.languageServerCommand(paths, script)
        assertEquals(launcher.path, cmd.first())
        assertEquals(script.path, cmd.last())
        assertEquals(emptyList<String>(), cmd.filter { it == "-I" || it == "-E" || it == "-s" })
    }
}
