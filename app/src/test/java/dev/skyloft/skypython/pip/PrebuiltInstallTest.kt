package dev.skyloft.skypython.pip

import dev.skyloft.skypython.R
import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.userBase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [PipRepository] with SkyPython's wheel index in front of PyPI. */
class PrebuiltInstallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var paths: PythonPaths
    private val calls = mutableListOf<List<String>>()
    private val runner = PipRunner { module, args ->
        calls += listOf(module) + args
        flowOf(ConsoleEvent.Stdout("ok\n"), ConsoleEvent.Exited(0))
    }

    private fun wheel(name: String, version: String, abi: String = "x86_64", requires: List<String> = emptyList()) =
        IndexWheel(name, version, abi, "$name-$version-cp314-cp314-android_24_$abi.whl", "0".repeat(64), 1000, requires, null)

    private val index = WheelIndexData(
        "cp314",
        mapOf(
            "numpy" to listOf(wheel("numpy", "2.5.4")),
            "pandas" to listOf(wheel("pandas", "3.0.0", requires = listOf("numpy"))),
            "armonly" to listOf(wheel("armonly", "1.0", abi = "arm64-v8a")),
        ),
    )

    private inner class FakeWheels(var catalog: WheelCatalog?) : PrebuiltWheels {
        val downloaded = mutableListOf<String>()
        var fail: Exception? = null
        /** Failures for the next downloads only (then they succeed). */
        val failOnce = ArrayDeque<Exception>()
        /** What a fresh index read returns. */
        var fresh: WheelCatalog? = null
        var refreshes = 0
        override val wheelDir: File get() = File(tmp.root, "wheels")
        override suspend fun catalog() = catalog
        override suspend fun refresh(): WheelCatalog? {
            refreshes++
            fresh?.let { catalog = it }
            return catalog
        }
        override suspend fun download(wheel: IndexWheel, progress: suspend (Long, Long) -> Unit): File {
            fail?.let { throw it }
            failOnce.removeFirstOrNull()?.let { throw it }
            progress(0, wheel.size)
            progress(500, wheel.size)
            progress(wheel.size, wheel.size)
            downloaded += wheel.file
            return File(wheelDir, wheel.file)
        }
    }

    private lateinit var wheels: FakeWheels
    private lateinit var repo: PipRepository

    @Before
    fun setUp() {
        val home = File(tmp.root, "home").apply { mkdirs() }
        paths = PythonPaths(File(tmp.root, "python"), tmp.root, File(tmp.root, "launcher"), home, File(tmp.root, "cache"))
        File(paths.userBase, PipRepository.MARKER).apply { parentFile!!.mkdirs(); writeText("x") }
        wheels = FakeWheels(WheelCatalog(index, "x86_64"))
        repo = PipRepository(install = { Result.success(paths) }, runnerFor = { runner }, wheels = wheels)
    }

    private val base = listOf("pip", "install", "--user", "--no-input", "--progress-bar", "off")
    private fun findLinks() = listOf("--find-links", wheels.wheelDir.absolutePath, "--prefer-binary")

    @Test
    fun aPrebuiltPackageIsDownloadedThenInstalledFromTheFolder() = runTest {
        val log = repo.install("numpy").toList().joinToString("")
        assertEquals(listOf("numpy-2.5.4-cp314-cp314-android_24_x86_64.whl"), wheels.downloaded)
        assertEquals(listOf(base + findLinks() + "numpy==2.5.4"), calls)
        assertTrue(log, log.contains("numpy 2.5.4"))
        assertTrue(log, log.contains("100%"))
        assertTrue(log, log.contains("ok\n"))
    }

    @Test
    fun requirementsAreDownloadedFirstAndInstalledTogether() = runTest {
        repo.install("pandas==3.0.0").toList()
        assertEquals(
            listOf("numpy-2.5.4-cp314-cp314-android_24_x86_64.whl", "pandas-3.0.0-cp314-cp314-android_24_x86_64.whl"),
            wheels.downloaded,
        )
        assertEquals(listOf(base + findLinks() + listOf("numpy==2.5.4", "pandas==3.0.0")), calls)
    }

    @Test
    fun extrasAreKept() = runTest {
        repo.install("numpy[dev]").toList()
        assertEquals(listOf(base + findLinks() + "numpy[dev]==2.5.4"), calls)
    }

    @Test
    fun upgradeUsesTheIndexToo() = runTest {
        repo.upgrade("numpy").toList()
        assertEquals(listOf(base + "--upgrade" + findLinks() + "numpy==2.5.4"), calls)
    }

    @Test
    fun otherPackagesGoToPyPiAsBefore() = runTest {
        repo.install("requests").toList()
        assertEquals(listOf(base + "requests"), calls)
        assertTrue(wheels.downloaded.isEmpty())
    }

    @Test
    fun noIndexMeansPlainPip() = runTest {
        wheels.catalog = null
        repo.install("numpy").toList()
        assertEquals(listOf(base + "numpy"), calls)
    }

    @Test
    fun anotherAbiOnlyGivesAClearErrorWithoutRunningPip() = runTest {
        try {
            repo.install("armonly").toList()
            fail("expected PrebuiltUnavailableException")
        } catch (e: PrebuiltUnavailableException) {
            assertEquals("armonly", e.name)
            assertEquals("x86_64", e.abi)
            assertEquals(setOf("arm64-v8a"), e.abis)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aVersionThatIsNotPrebuiltIsExplained() = runTest {
        try {
            repo.install("numpy==1.26.0").toList()
            fail("expected PrebuiltVersionException")
        } catch (e: PrebuiltVersionException) {
            assertEquals(listOf("2.5.4"), e.versions)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aWheelThatChangedSinceTheCachedIndexIsRetriedWithAFreshIndex() = runTest {
        // The index on the phone is older than the wheel on the server.
        wheels.failOnce += WheelVerificationException("numpy.whl", "aa", "bb")
        wheels.fresh = WheelCatalog(
            WheelIndexData("cp314", mapOf("numpy" to listOf(wheel("numpy", "2.5.5")))),
            "x86_64",
        )
        repo.install("numpy").toList()
        assertEquals(1, wheels.refreshes)
        assertEquals(listOf(base + findLinks() + "numpy==2.5.5"), calls)
    }

    @Test
    fun aFailedDownloadStopsBeforePip() = runTest {
        wheels.fail = WheelVerificationException("numpy.whl", "aa", "bb")
        try {
            repo.install("numpy").toList()
            fail("expected WheelVerificationException")
        } catch (e: WheelVerificationException) {
            // expected
        }
        // One retry with a fresh index, then it gives up.
        assertEquals(1, wheels.refreshes)
        assertTrue(calls.isEmpty())
    }

    // --- Messages --------------------------------------------------------------------------

    private fun text(id: Int, args: Array<out Any>) = "$id:" + args.joinToString("|")

    @Test
    fun errorsAreDescribed() {
        assertEquals(
            "${R.string.pip_error_prebuilt_32bit}:numpy",
            describePipError(PrebuiltUnavailableException("numpy", "armeabi-v7a", setOf("arm64-v8a")), ::text),
        )
        assertEquals(
            "${R.string.pip_error_prebuilt_abi}:numpy|riscv64",
            describePipError(PrebuiltUnavailableException("numpy", "riscv64", setOf("arm64-v8a")), ::text),
        )
        assertEquals(
            "${R.string.pip_error_prebuilt_version}:numpy|==1.0|2.5.4, 2.4.0",
            describePipError(PrebuiltVersionException("numpy", "==1.0", listOf("2.5.4", "2.4.0")), ::text),
        )
        assertEquals(
            "${R.string.pip_error_wheel_checksum}:numpy.whl",
            describePipError(WheelVerificationException("numpy.whl", "aa", "bb"), ::text),
        )
    }

    // --- Popular list ----------------------------------------------------------------------

    @Test
    fun indexPresenceWinsInThePopularList() {
        val numpy = PopularPackages.all.first { it.name == "numpy" }
        val pillow = PopularPackages.all.first { it.name == "pillow" }
        val requests = PopularPackages.all.first { it.name == "requests" }
        val jinja = PopularPackages.all.first { it.name == "jinja2" }
        val avail = PrebuiltStatus.Available(wheel("numpy", "2.5.4"))
        val other = PrebuiltStatus.OtherAbis(setOf("arm64-v8a"))

        assertEquals(PopularAction.Prebuilt, PopularPackages.action(numpy, avail, is64Bit = true))
        assertEquals(PopularAction.ComingLater, PopularPackages.action(numpy, null, is64Bit = true))
        assertEquals(PopularAction.Not32Bit, PopularPackages.action(numpy, other, is64Bit = false))
        assertEquals(PopularAction.NotThisDevice, PopularPackages.action(numpy, other, is64Bit = true))
        assertEquals(PopularAction.Prebuilt, PopularPackages.action(pillow, avail, is64Bit = false))
        assertEquals(PopularAction.Install, PopularPackages.action(requests, null, is64Bit = false))
        assertEquals(PopularAction.Needs64Bit, PopularPackages.action(jinja, null, is64Bit = false))
        assertEquals(PopularAction.Install, PopularPackages.action(jinja, null, is64Bit = true))
    }
}
