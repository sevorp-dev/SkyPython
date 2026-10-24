package dev.skyloft.skypython.pip

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.runtime.PythonProcess
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Real pip against the bundled interpreter. The install test needs network (else skipped). */
@RunWith(AndroidJUnit4::class)
class PipInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PipRepository.create(context)

    @Test
    fun ensurePipBootstrapsOfflineAndPipRuns() = runBlocking {
        withTimeout(BOOTSTRAP_TIMEOUT_MS) {
            repo.ensurePip().getOrThrow()
            val version = repo.pipVersion().getOrThrow()
            assertTrue(version, version.startsWith("pip "))
            // An empty or populated list, but always parseable.
            repo.listInstalled().getOrThrow()
        }
    }

    @Test
    fun installedPackageImportsFromAScript() = runBlocking {
        assumeTrue("no network to PyPI", canReach("pypi.org") && canReach("files.pythonhosted.org"))
        withTimeout(INSTALL_TIMEOUT_MS) {
            val log = StringBuilder()
            try {
                repo.install("six").collect { log.append(it) }
            } catch (e: PipFailedException) {
                throw AssertionError("pip install six failed: ${e.message}\n$log", e)
            }
            val installed = repo.listInstalled().getOrThrow()
            assertTrue(installed.toString(), installed.any { it.name.equals("six", ignoreCase = true) })

            // A plain script (not pip) sees the user site through PYTHONUSERBASE.
            val paths = PythonInstaller(context).ensureInstalled().getOrThrow()
            val dir = File(context.filesDir, "pip-test").apply { deleteRecursively(); mkdirs() }
            val script = File(dir, "use_six.py").apply {
                writeText("import six\nprint('six', six.__version__)\n")
            }
            val events = PythonProcess(paths).start(script).toList()
            val out = events.filterIsInstance<ConsoleEvent.Stdout>().joinToString("") { it.text }
            val err = events.filterIsInstance<ConsoleEvent.Stderr>().joinToString("") { it.text }
            assertTrue("stdout=$out stderr=$err", out.startsWith("six "))
            assertEquals(ConsoleEvent.Exited(0), events.last())

            repo.uninstall("six").getOrThrow()
            assertTrue(repo.listInstalled().getOrThrow().none { it.name.equals("six", ignoreCase = true) })
        }
    }

    private fun canReach(host: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, 443), 5_000) }
        true
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val BOOTSTRAP_TIMEOUT_MS = 180_000L
        const val INSTALL_TIMEOUT_MS = 300_000L
    }
}
