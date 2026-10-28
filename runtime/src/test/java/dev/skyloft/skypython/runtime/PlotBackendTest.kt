package dev.skyloft.skypython.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlotBackendTest {

    @get:Rule val tmp = TemporaryFolder()

    private val files = File("/data/user/0/dev.skyloft.skypython/files")
    private val cache = File("/data/user/0/dev.skyloft.skypython/cache")
    private val nativeLibDir = File("/data/app/~~abc/dev.skyloft.skypython-1/lib/x86_64")
    private val paths = PythonPaths(
        home = File(files, "python"),
        nativeLibDir = nativeLibDir,
        launcher = File(nativeLibDir, "libpylauncher.so"),
        homeDir = File(files, "home"),
        tmpDir = cache,
    )

    @Test
    fun backendLivesNextToPythonAndPlotsInTheCache() {
        assertEquals(File(files, "plot"), paths.plotBackendDir)
        assertEquals(File(cache, "plots"), paths.plotDir)
    }

    @Test
    fun everyProgramSelectsTheBackend() {
        val projects = File(files, "projects")
        val commands = listOf(RunCommand.Script(File(projects, "a.py")), RunCommand.Interpreter(projects), RunCommand.Shell(projects))
        for (command in commands) {
            val env = PtyCommand.of(paths, command, mapOf("PATH" to "/system/bin")).env.associate {
                it.substringBefore('=') to it.substringAfter('=')
            }
            assertEquals(command.toString(), "module://skypython_plot", env["MPLBACKEND"])
            assertEquals(paths.plotBackendDir.path, env["PYTHONPATH"])
            assertEquals(paths.plotDir.path, env["SKYPYTHON_PLOT_DIR"])
        }
    }

    @Test
    fun usersOwnBackendAndPythonPathAreKept() {
        val env = mutableMapOf("MPLBACKEND" to "agg", "PYTHONPATH" to "/sdcard/lib", "HOME" to "/old")
        PythonEnvironment.applyTo(env, PythonEnvironment.build(paths))
        assertEquals("agg", env["MPLBACKEND"])
        assertEquals(paths.plotBackendDir.path + File.pathSeparator + "/sdcard/lib", env["PYTHONPATH"])
        assertEquals(paths.homeDir.path, env["HOME"])
        // Applying twice does not repeat the folder.
        val again = mutableMapOf("PYTHONPATH" to paths.plotBackendDir.path)
        PythonEnvironment.applyTo(again, PythonEnvironment.build(paths))
        assertEquals(paths.plotBackendDir.path, again["PYTHONPATH"])
    }

    @Test
    fun moduleIsCopiedOnceAndReplacedWhenTheBundledOneChanges() {
        val dir = File(tmp.root, "plot")
        val v1 = "# v1".toByteArray()
        val module = PlotBackend.ensureInstalled(dir) { v1.inputStream() }
        assertEquals(File(dir, "skypython_plot.py"), module)
        assertArrayEquals(v1, module.readBytes())
        val v2 = "# v2".toByteArray()
        PlotBackend.ensureInstalled(dir) { v2.inputStream() }
        assertArrayEquals(v2, module.readBytes())
        assertFalse(File(dir, "skypython_plot.py.part").exists())
    }

    @Test
    fun bundledModuleSpeaksTheTerminalsProtocol() {
        val asset = listOf(File("src/main/assets", PlotBackend.ASSET), File("runtime/src/main/assets", PlotBackend.ASSET))
            .first { it.isFile }
        val text = asset.readText()
        assertTrue(text.contains("OSC = 7777"))
        assertTrue(text.contains("\"\\033]%d;plot;%s\\007\""))
    }

    @Test
    fun oldRunsAreDeletedRecentOnesKept() {
        val plots = tmp.newFolder("plots")
        val now = 10L * PlotBackend.KEEP_MS
        val long = now - PlotBackend.KEEP_MS - 1000
        val old = File(plots, "1-a/1").apply { mkdirs() }
        File(old, "figure-1.png").apply { writeText("x"); setLastModified(long) }
        old.setLastModified(long)
        old.parentFile!!.setLastModified(long)
        val recent = File(plots, "2-b/1").apply { mkdirs() }
        File(recent, "figure-1.png").apply { writeText("x"); setLastModified(now - 1000) }
        PlotBackend.deleteOld(plots, now)
        assertFalse(File(plots, "1-a").exists())
        assertTrue(File(plots, "2-b/1/figure-1.png").exists())
        PlotBackend.deleteOld(File(tmp.root, "missing"), now) // no folder yet: nothing happens
    }
}
