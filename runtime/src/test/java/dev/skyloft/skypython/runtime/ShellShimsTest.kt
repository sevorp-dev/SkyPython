package dev.skyloft.skypython.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShellShimsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val launcher = File("/data/app/~~abc/dev.skyloft.skypython-1/lib/x86_64/libpylauncher.so")

    @Test
    fun rcDefinesPythonAndPipAsFunctionsCallingTheLauncher() {
        // Functions, not scripts: Android 10+ can't exec files from app storage.
        val l = launcher.path
        val rc = ShellShims.rc(launcher)
        assertTrue(rc, rc.contains("python() { '$l' \"\$@\"; }\n"))
        assertTrue(rc, rc.contains("python3() { '$l' \"\$@\"; }\n"))
        assertTrue(rc, rc.contains("pip() { '$l' -m pip \"\$@\"; }\n"))
        assertTrue(rc, rc.contains("pip3() { '$l' -m pip \"\$@\"; }\n"))
    }

    @Test
    fun rcSetsAShortPromptWithTheFolderName() {
        assertTrue(ShellShims.rc(launcher).contains("PS1='\${PWD##*/} \$ '\n"))
    }

    @Test
    fun singleQuotesInTheLauncherPathAreEscaped() {
        val odd = File("/x/it's/libpylauncher.so")
        val quoted = "'" + odd.path.replace("'", "'\\''") + "'"
        assertTrue(ShellShims.rc(odd).contains("python() { $quoted \"\$@\"; }"))
    }

    @Test
    fun installWritesTheRcFileCreatingItsFolder() {
        val rc = File(tmp.root, "home/.skypythonrc")
        ShellShims.install(rc, launcher)
        assertEquals(ShellShims.rc(launcher), rc.readText())
    }

    @Test
    fun installRewritesAStaleRc() {
        val rc = File(tmp.root, ".skypythonrc").apply { writeText("python() { /old \"\$@\"; }\n") }
        ShellShims.install(rc, launcher)
        assertEquals(ShellShims.rc(launcher), rc.readText())
    }

    @Test
    fun noTtyWarningsAreDroppedFromShellOutput() {
        val chunk = "/system/bin/sh: can't find tty fd: No such device or address\n" +
            "/system/bin/sh: warning: won't have full job control\n" +
            "projects \$ "
        assertEquals("projects \$ ", ShellShims.stripNoise(chunk))
    }

    @Test
    fun otherShellErrorsAreKept() {
        val chunk = "/system/bin/sh: foo: inaccessible or not found\n"
        assertEquals(chunk, ShellShims.stripNoise(chunk))
    }
}
