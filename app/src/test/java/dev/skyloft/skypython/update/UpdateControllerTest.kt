package dev.skyloft.skypython.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hour = 60 * 60 * 1000L
    private var now = 100 * hour
    private val http = FakeUpdateHttp()
    private val prefs = MemoryUpdatePrefs()
    private val installer = RecordingInstaller()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val apk = ByteArray(10_000) { it.toByte() }
    private val v6 = SemVer(0, 0, 6)

    @After
    fun stop() = scope.cancel()

    private fun controller(current: String = "0.0.5", variant: String = "x86_64") = UpdateController(
        http = http,
        prefs = prefs,
        installer = installer,
        currentVersion = current,
        variant = variant,
        downloadDir = File(tmp.root, "updates"),
        scope = scope.backgroundScope,
        clock = { now },
        io = dispatcher,
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun release(version: SemVer = v6, variant: String = "x86_64") {
        http.heads[Releases.LATEST_URL] = 302 to Releases.pageUrl(version)
        http.files[Releases.notesUrl(version)] = "## What's new\n- Things\n".toByteArray()
        http.files[Releases.apkUrl(version, variant)] = apk
        http.files[Releases.sumsUrl(version)] = "${sha(apk)}  ${Releases.apkName(version, variant)}\n".toByteArray()
    }

    private fun latestChecks() = http.requests.count { it.first == Releases.LATEST_URL }

    @Test
    fun autoCheckFindsANewerReleaseAndRemembersIt() = scope.runTest {
        release()
        val c = controller()
        c.start()
        runCurrent()
        val s = c.state.value
        assertEquals(v6, s.available)
        assertEquals("## What's new\n- Things\n", s.notes)
        assertTrue(s.badge)
        assertTrue(s.banner)
        assertEquals(now, prefs.value.lastCheckAt)
        assertEquals("0.0.6", prefs.value.latest)
    }

    @Test
    fun autoCheckRunsAtMostEvery12Hours() = scope.runTest {
        release()
        controller().start()
        runCurrent()
        assertEquals(1, latestChecks())
        now += 11 * hour
        val again = controller()
        again.start()
        runCurrent()
        assertEquals(1, latestChecks())
        // Still announced from what the last check found.
        assertEquals(v6, again.state.value.available)
        now += 1 * hour
        controller().start()
        runCurrent()
        assertEquals(2, latestChecks())
    }

    @Test
    fun autoCheckOffMeansNoRequest() = scope.runTest {
        release()
        prefs.value = prefs.value.copy(autoCheck = false)
        controller().start()
        runCurrent()
        assertEquals(0, latestChecks())
    }

    @Test
    fun autoCheckOfflineIsSilentAndRetriedNextStart() = scope.runTest {
        http.failing += Releases.LATEST_URL
        val c = controller()
        c.start()
        runCurrent()
        assertNull(c.state.value.message)
        assertNull(c.state.value.available)
        assertNull(prefs.value.lastCheckAt)
    }

    @Test
    fun manualCheckReportsUpToDateAndOffline() = scope.runTest {
        release(SemVer(0, 0, 5))
        val c = controller()
        c.checkNow()
        runCurrent()
        assertEquals(UpdateMessage.UpToDate("0.0.5"), c.state.value.message)
        c.consumeMessage()
        assertNull(c.state.value.message)
        http.failing += Releases.LATEST_URL
        c.checkNow()
        runCurrent()
        assertEquals(UpdateMessage.CheckFailed, c.state.value.message)
    }

    @Test
    fun manualCheckWithAnUpdateOpensIt() = scope.runTest {
        release()
        val c = controller()
        c.checkNow()
        runCurrent()
        assertEquals(UpdateMessage.Available("0.0.6"), c.state.value.message)
    }

    @Test
    fun anInstalledUpdateIsForgotten() = scope.runTest {
        prefs.value = prefs.value.copy(latest = "0.0.6", lastCheckAt = now)
        val c = controller(current = "0.0.6")
        c.start()
        runCurrent()
        assertNull(c.state.value.available)
    }

    @Test
    fun skipHidesBadgeAndBannerUntilANewerRelease() = scope.runTest {
        release()
        val c = controller()
        c.start()
        runCurrent()
        c.skip()
        runCurrent()
        assertFalse(c.state.value.badge)
        assertFalse(c.state.value.banner)
        assertEquals("0.0.6", prefs.value.skipped)
        // 0.0.7 comes out.
        now += 13 * hour
        release(SemVer(0, 0, 7))
        val later = controller()
        later.start()
        runCurrent()
        assertTrue(later.state.value.badge)
        assertTrue(later.state.value.banner)
    }

    @Test
    fun manualCheckBringsASkippedVersionBack() = scope.runTest {
        release()
        prefs.value = prefs.value.copy(skipped = "0.0.6")
        val c = controller()
        c.checkNow()
        runCurrent()
        assertTrue(c.state.value.badge)
        assertNull(prefs.value.skipped)
    }

    @Test
    fun dismissedBannerKeepsTheBadge() = scope.runTest {
        release()
        val c = controller()
        c.start()
        runCurrent()
        c.dismissBanner()
        runCurrent()
        assertFalse(c.state.value.banner)
        assertTrue(c.state.value.badge)
    }

    @Test
    fun installDownloadsVerifiesAndHandsTheApkToTheSystem() = scope.runTest {
        release()
        val c = controller()
        c.start()
        runCurrent()
        c.install()
        runCurrent()
        val file = installer.installed.single()
        assertEquals("SkyPython-0.0.6-x86_64.apk", file.name)
        assertEquals(sha(apk), sha(file.readBytes()))
        assertEquals(UpdatePhase.Installing, c.state.value.phase)
        installer.results.emit(InstallResult.Cancelled)
        runCurrent()
        assertEquals(UpdatePhase.Idle, c.state.value.phase)
    }

    @Test
    fun universalInstallDownloadsTheUniversalApk() = scope.runTest {
        release(variant = "universal")
        val c = controller(variant = "universal")
        c.start()
        runCurrent()
        c.install()
        runCurrent()
        assertEquals("SkyPython-0.0.6-universal.apk", installer.installed.single().name)
    }

    @Test
    fun badChecksumIsReportedAndNothingInstalled() = scope.runTest {
        release()
        http.files[Releases.sumsUrl(v6)] = "${sha(byteArrayOf(1))}  SkyPython-0.0.6-x86_64.apk\n".toByteArray()
        val c = controller()
        c.start()
        runCurrent()
        c.install()
        runCurrent()
        assertEquals(UpdatePhase.Failed(UpdateFailure.Checksum), c.state.value.phase)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun withoutInstallPermissionItAsksAndContinuesAfterwards() = scope.runTest {
        release()
        installer.allowed = false
        val c = controller()
        c.start()
        runCurrent()
        c.install()
        runCurrent()
        assertTrue(c.state.value.needsInstallPermission)
        assertTrue(installer.installed.isEmpty())
        // Back from Settings, still not allowed: keep asking.
        c.onResume()
        runCurrent()
        assertTrue(c.state.value.needsInstallPermission)
        installer.allowed = true
        c.onResume()
        runCurrent()
        assertFalse(c.state.value.needsInstallPermission)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun failedInstallExplainsAnIncompatibleSignature() = scope.runTest {
        release()
        val c = controller()
        c.start()
        runCurrent()
        c.install()
        runCurrent()
        installer.results.emit(InstallResult.Failed(5, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"))
        runCurrent()
        val phase = c.state.value.phase as UpdatePhase.Failed
        val failure = phase.failure as UpdateFailure.Install
        assertTrue(failure.incompatible)
    }

    @Test
    fun cancelStopsTheDownload() = scope.runTest {
        release()
        val c = controller()
        c.start()
        runCurrent()
        c.cancel()
        assertEquals(UpdatePhase.Idle, c.state.value.phase)
    }

    private class RecordingInstaller : ApkInstaller {
        var allowed = true
        val installed = mutableListOf<File>()
        override val results = MutableSharedFlow<InstallResult>()
        override fun canInstall(): Boolean = allowed
        override suspend fun install(file: File) {
            installed += file
        }
    }
}
