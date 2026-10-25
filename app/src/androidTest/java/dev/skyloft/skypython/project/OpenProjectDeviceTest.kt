package dev.skyloft.skypython.project

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.files.FilesViewModel
import dev.skyloft.skypython.files.Workspace
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.terminal.TerminalKind
import dev.skyloft.skypython.terminal.TerminalViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Open project" on a real device: a folder in shared storage (`Documents/SkyTest`) becomes the
 * project, Files works in it, a script runs in it (relative paths land there), and the recent
 * projects and the open project survive a new [ProjectsViewModel] (an app restart).
 */
@RunWith(AndroidJUnit4::class)
class OpenProjectDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val privateRoot = File(app.filesDir, "projects").canonicalFile

    @Suppress("DEPRECATION")
    private val project = File(Environment.getExternalStorageDirectory(), "Documents/SkyTest")
    private val terminals = mutableListOf<TerminalViewModel>()

    @Before
    fun grantStorageAccess() {
        val pkg = app.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        } else {
            instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.READ_EXTERNAL_STORAGE)
            instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        waitFor("storage access") { StoragePermission.granted(app).takeIf { it } }
        project.deleteRecursively()
        assertTrue("cannot create $project", project.mkdirs())
    }

    @After
    fun tearDown() {
        onMain { terminals.forEach { it.kill() } }
        project.deleteRecursively()
        onMain { ProjectsViewModel(app, privateRoot).apply { openPrivate(); forget(project.canonicalPath) } }
        Thread.sleep(300) // let DataStore write
    }

    @Test
    fun aFolderInSharedStorageBecomesTheProjectAndIsRemembered() {
        val vm = onMain { ProjectsViewModel(app, privateRoot) }
        assertTrue(onMain { vm.needsPermission(project) })
        assertTrue(onMain { vm.access.value })
        var result: String? = "pending"
        onMain { vm.open(project) { result = it } }
        waitFor("open") { if (result != "pending") true else null }
        assertEquals(null, result)
        assertEquals(project.canonicalPath, onMain { vm.current.value.path })

        // A restart: the new view model reopens the same project and lists it first in recents.
        val again = onMain { ProjectsViewModel(app, privateRoot) }
        waitFor("restored project") { onMain { again.current.value.path }.takeIf { it == project.canonicalPath } }
        val recents = waitFor("recents") { onMain { again.recents.value }.takeIf { it.isNotEmpty() } }
        assertEquals(project.canonicalPath, recents.first().path)
    }

    @Test
    fun aMissingProjectFallsBackToTheAppsOwnProjects() {
        val vm = onMain { ProjectsViewModel(app, privateRoot) }
        var result: String? = "pending"
        onMain { vm.open(project) { result = it } }
        waitFor("open") { if (result != "pending") true else null }
        project.deleteRecursively()
        val again = onMain { ProjectsViewModel(app, privateRoot) }
        Thread.sleep(500)
        assertEquals(privateRoot.path, onMain { again.current.value.path })
    }

    @Test
    fun filesCreatesInSharedStorageAndAScriptWritesRelativeFilesThere() {
        val files = onMain { FilesViewModel(Workspace(privateRoot)) }
        onMain { files.setRoot(project.canonicalFile, create = false) }
        waitFor("project listed") { onMain { files.state.value }.takeIf { it.root.path == project.canonicalPath && it.rootAvailable } }
        var error: String? = "pending"
        onMain { files.createFile("main.py") { error = it } }
        waitFor("created") { if (error != "pending") true else null }
        assertEquals(null, error)
        val script = File(project, "main.py")
        assertTrue(script.isFile)
        waitFor("listed") { onMain { files.state.value.entries }.takeIf { e -> e.any { it.name == "main.py" } } }

        script.writeText(
            """
            import os
            with open("out.txt", "w") as f:
                f.write(os.getcwd())
            """.trimIndent() + "\n",
        )
        val installer = PythonInstaller(app)
        val run = onMain {
            TerminalViewModel(TerminalKind.Run, { project.canonicalFile }, app, installer::ensureInstalled).also {
                terminals += it
                it.run(script)
            }
        }
        val session = waitFor("a session") { onMain { run.state.value.session } }
        onMain { session.updateSize(80, 24, 10, 20) }
        val out = File(project, "out.txt")
        waitFor("out.txt", timeoutMs = 120_000) { out.takeIf { it.isFile && it.length() > 0 } }
        assertEquals(project.canonicalPath, File(out.readText()).canonicalPath)
    }

    private fun shell(command: String) {
        val out = instrumentation.uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() } // waits for the command
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 10_000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
