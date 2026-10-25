package dev.skyloft.skypython.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DebuggerRuntimeTest {

    @get:Rule val tmp = TemporaryFolder()

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

    @Test
    fun debugCommandRunsTheDriverWithTheChannelThenTheScript() {
        val script = File(files, "projects/my app/main.py")
        val driver = File(files, "debugger/skydbg.py")
        val channel = File(paths.tmpDir, "dbg-1.sock")
        val cmd = RunCommand.Debug(script, driver, channel)
        assertEquals(
            listOf(launcher.path, "-u", driver.path, "--channel", "unix:" + channel.path, script.path),
            PythonEnvironment.command(paths, cmd),
        )
        // Same directory and variables as a normal run: the program can't tell the difference.
        assertEquals(RunCommand.Script(script).workingDir, cmd.workingDir)
        assertEquals(PythonEnvironment.build(paths), PythonEnvironment.environment(paths, cmd))
    }

    @Test(expected = IllegalArgumentException::class)
    fun debugSessionsDoNotRunInATerminal() {
        PtyCommand.of(paths, RunCommand.Debug(File("a.py"), File("d.py"), File("c")), emptyMap())
    }

    @Test
    fun driverIsCopiedOnceAndReplacedWhenTheBundledOneChanges() {
        val dir = File(tmp.root, "debugger")
        val v1 = "print('v1')".toByteArray()
        var opened = 0
        val script = DebuggerBundle.ensureInstalled(dir) { opened++; v1.inputStream() }
        assertEquals(File(dir, "skydbg.py"), script)
        assertArrayEquals(v1, script.readBytes())
        // Same content: the file is left alone.
        val stamp = script.lastModified()
        DebuggerBundle.ensureInstalled(dir) { opened++; v1.inputStream() }
        assertEquals(stamp, script.lastModified())
        val v2 = "print('v2')".toByteArray()
        DebuggerBundle.ensureInstalled(dir) { v2.inputStream() }
        assertArrayEquals(v2, script.readBytes())
        assertEquals(listOf("skydbg.py"), dir.list()!!.toList())
        assertTrue(opened >= 2)
    }

    @Test
    fun channelPathsAreUniqueShortAndInsideTheGivenDirectory() {
        val dir = File("/data/user/0/dev.skyloft.skypython/cache")
        val a = DebuggerBundle.channelFile(dir)
        val b = DebuggerBundle.channelFile(dir)
        assertEquals(dir, a.parentFile)
        assertFalse(a == b)
        // sockaddr_un holds at most 107 bytes of path.
        assertTrue(a.path.toByteArray().size < 108)
    }
}
