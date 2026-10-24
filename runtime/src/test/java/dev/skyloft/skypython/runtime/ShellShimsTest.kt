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
    fun shimScriptsExecTheLauncher() {
        val l = launcher.path
        assertEquals(
            mapOf(
                "python" to "#!/system/bin/sh\nexec '$l' \"\$@\"\n",
                "python3" to "#!/system/bin/sh\nexec '$l' \"\$@\"\n",
                "pip" to "#!/system/bin/sh\nexec '$l' -m pip \"\$@\"\n",
            ),
            ShellShims.scripts(launcher),
        )
    }

    @Test
    fun singleQuotesInTheLauncherPathAreEscaped() {
        val odd = File("/x/it's/libpylauncher.so")
        assertEquals(
            "#!/system/bin/sh\nexec '${odd.path.replace("'", "'\\''")}' \"\$@\"\n",
            ShellShims.scripts(odd).getValue("python"),
        )
    }

    @Test
    fun installWritesExecutableShimsCreatingTheBinDir() {
        val bin = File(tmp.root, "home/bin")
        ShellShims.install(bin, launcher)
        for ((name, content) in ShellShims.scripts(launcher)) {
            val f = File(bin, name)
            assertEquals(content, f.readText())
            assertTrue("$name must be executable", f.canExecute())
        }
    }

    @Test
    fun installRewritesAStaleShim() {
        val bin = File(tmp.root, "bin").apply { mkdirs() }
        File(bin, "python").writeText("#!/system/bin/sh\nexec /old/launcher \"\$@\"\n")
        ShellShims.install(bin, launcher)
        assertEquals(ShellShims.scripts(launcher).getValue("python"), File(bin, "python").readText())
    }
}
