package dev.skyloft.skypython.pip

import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.userBase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PipRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var paths: PythonPaths
    private val calls = mutableListOf<List<String>>()
    /** Events per call, keyed "ensurepip" or "pip <first arg>" ("pip install", ...). */
    private val replies = mutableMapOf<String, MutableList<List<ConsoleEvent>>>()
    private var installResult: Result<PythonPaths>? = null

    private val runner = PipRunner { module, args ->
        calls += listOf(module) + args
        val key = if (module == "pip") "pip ${args.first()}" else module
        val queue = replies[key]
        val events = if (queue.isNullOrEmpty()) listOf(ConsoleEvent.Exited(0)) else queue.removeAt(0)
        flowOf(*events.toTypedArray())
    }

    private fun reply(key: String, vararg events: ConsoleEvent) {
        replies.getOrPut(key) { mutableListOf() } += events.toList()
    }

    private lateinit var repo: PipRepository

    @Before
    fun setUp() {
        val root = tmp.root
        val home = File(root, "home").apply { mkdirs() }
        paths = PythonPaths(File(root, "python"), root, File(root, "launcher"), home, File(root, "cache"))
        repo = PipRepository(
            install = { installResult ?: Result.success(paths) },
            runnerFor = { p -> assertEquals(paths, p); runner },
        )
    }

    private val ensurepip = listOf("ensurepip", "--user", "--default-pip")

    @Test
    fun ensurePipRunsOnceAndLeavesAMarker() = runTest {
        assertTrue(repo.ensurePip().isSuccess)
        assertTrue(repo.ensurePip().isSuccess)
        assertEquals(listOf(ensurepip), calls)
        assertTrue(File(paths.userBase, PipRepository.MARKER).isFile)
    }

    @Test
    fun failedEnsurePipIsRetriedNextTime() = runTest {
        reply("ensurepip", ConsoleEvent.Stderr("boom\n"), ConsoleEvent.Exited(1))
        val first = repo.ensurePip()
        assertTrue(first.isFailure)
        assertTrue(first.exceptionOrNull()!!.message!!.contains("boom"))
        assertFalse(File(paths.userBase, PipRepository.MARKER).exists())
        assertTrue(repo.ensurePip().isSuccess)
        assertEquals(listOf(ensurepip, ensurepip), calls)
    }

    @Test
    fun installStreamsBootstrapAndPipOutput() = runTest {
        reply("ensurepip", ConsoleEvent.Stdout("Installing pip\n"), ConsoleEvent.Exited(0))
        reply(
            "pip install",
            ConsoleEvent.Stdout("Collecting six\n"),
            ConsoleEvent.Stderr("WARNING: x\n"),
            ConsoleEvent.Stdout("Successfully installed six-1.16.0\n"),
            ConsoleEvent.Exited(0),
        )
        val log = repo.install("six").toList().joinToString("")
        assertTrue(log, log.contains("Installing pip\n"))
        assertTrue(log, log.contains("Collecting six\nWARNING: x\nSuccessfully installed six-1.16.0\n"))
        assertEquals(
            listOf(
                ensurepip,
                listOf("pip", "install", "--user", "--no-input", "--progress-bar", "off", "six"),
            ),
            calls,
        )
    }

    @Test
    fun installFailureThrowsAfterTheLog() = runTest {
        reply("pip install", ConsoleEvent.Stderr("ERROR: nope\n"), ConsoleEvent.Exited(1))
        val log = StringBuilder()
        try {
            repo.install("nosuchpkg").collect { log.append(it) }
            fail("expected PipFailedException")
        } catch (e: PipFailedException) {
            assertEquals(1, e.exitCode)
        }
        assertTrue(log.toString(), log.contains("ERROR: nope"))
    }

    @Test
    fun processThatCannotStartFailsTheInstall() = runTest {
        reply("pip install", ConsoleEvent.Failed("Cannot start Python"))
        try {
            repo.install("six").toList()
            fail("expected PipFailedException")
        } catch (e: PipFailedException) {
            assertEquals(null, e.exitCode)
            assertTrue(e.message!!.contains("Cannot start Python"))
        }
    }

    @Test
    fun missingPipModuleResetsTheMarker() = runTest {
        repo.ensurePip()
        reply("pip install", ConsoleEvent.Stderr("No module named pip\n"), ConsoleEvent.Exited(1))
        runCatching { repo.install("six").toList() }
        assertFalse(File(paths.userBase, PipRepository.MARKER).exists())
        repo.install("six").toList()
        assertEquals(ensurepip, calls[2])
    }

    @Test
    fun invalidSpecIsRejectedWithoutRunningAnything() = runTest {
        try {
            repo.install("six; ls").toList()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(emptyList<List<String>>(), calls)
    }

    @Test
    fun pythonInstallFailureFailsEverything() = runTest {
        installResult = Result.failure(IllegalStateException("no ABI"))
        assertTrue(repo.ensurePip().isFailure)
        assertTrue(repo.listInstalled().isFailure)
        assertTrue(repo.uninstall("six").isFailure)
        assertTrue(runCatching { repo.install("six").toList() }.isFailure)
        assertEquals(emptyList<List<String>>(), calls)
    }

    @Test
    fun listInstalledParsesStdoutAndHidesPip() = runTest {
        reply(
            "pip list",
            ConsoleEvent.Stderr("[notice] something\n"),
            ConsoleEvent.Stdout("[{\"name\": \"pip\", \"version\": \"25.2\"}, "),
            ConsoleEvent.Stdout("{\"name\": \"six\", \"version\": \"1.16.0\"}]\n"),
            ConsoleEvent.Exited(0),
        )
        assertEquals(listOf(PipPackage("six", "1.16.0")), repo.listInstalled().getOrThrow())
        assertEquals(listOf("pip", "list", "--user", "--format=json"), calls.last())
    }

    @Test
    fun malformedListIsAFailure() = runTest {
        reply("pip list", ConsoleEvent.Stdout("not json"), ConsoleEvent.Exited(0))
        assertTrue(repo.listInstalled().isFailure)
    }

    @Test
    fun uninstallRunsPipUninstallYes() = runTest {
        assertTrue(repo.uninstall("six").isSuccess)
        assertEquals(listOf("pip", "uninstall", "-y", "six"), calls.last())
    }

    @Test
    fun uninstallRejectsNonNames() = runTest {
        assertTrue(repo.uninstall("-r x").isFailure)
        assertEquals(emptyList<List<String>>(), calls)
    }

    @Test
    fun uninstallFailureCarriesTheOutput() = runTest {
        reply("pip uninstall", ConsoleEvent.Stderr("not installed\n"), ConsoleEvent.Exited(1))
        val r = repo.uninstall("six")
        assertTrue(r.exceptionOrNull()!!.message!!.contains("not installed"))
    }

    @Test
    fun versionReturnsStdout() = runTest {
        reply("pip --version", ConsoleEvent.Stdout("pip 25.2 from /x (python 3.14)\n"), ConsoleEvent.Exited(0))
        assertEquals("pip 25.2 from /x (python 3.14)", repo.pipVersion().getOrThrow())
    }
}
