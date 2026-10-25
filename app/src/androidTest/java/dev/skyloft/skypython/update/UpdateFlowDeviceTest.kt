package dev.skyloft.skypython.update

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.skyloft.skypython.MainActivity
import dev.skyloft.skypython.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The updater end to end in the app, GitHub and the system installer faked: the banner appears
 * on start, What's new shows the notes, Install downloads the APK of this build's variant,
 * verifies it against SHA256SUMS and hands it to the installer.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateFlowDeviceTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val version = SemVer(9, 9, 9)
    private val variant = dev.skyloft.skypython.BuildConfig.SKY_APK_VARIANT
    private val apk = ByteArray(3 * 1024 * 1024) { (it % 253).toByte() }
    private val installer = RecordingInstaller()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before
    fun fakeGitHub() {
        val files = mapOf(
            Releases.notesUrl(version) to "# SkyPython 9.9.9\n\n## What's new\n- Faster everything\n".toByteArray(),
            Releases.sumsUrl(version) to "${sha(apk)}  ${Releases.apkName(version, variant)}\n".toByteArray(),
            Releases.apkUrl(version, variant) to apk,
        )
        UpdateEnvironment.http = object : UpdateHttp {
            override fun head(url: String) =
                if (url == Releases.LATEST_URL) UpdateHead(302, Releases.pageUrl(version)) else UpdateHead(404, null)

            override fun get(url: String, headers: Map<String, String>): UpdateResponse {
                val bytes = files[url] ?: return UpdateResponse(404)
                return UpdateResponse(200, mapOf("content-length" to bytes.size.toString()), ByteArrayInputStream(bytes))
            }
        }
        UpdateEnvironment.installer = { installer }
        UpdateEnvironment.versionOverride = "0.0.5"
        resetPrefs()
    }

    @After
    fun restore() {
        scenario?.close()
        UpdateEnvironment.http = UrlUpdateHttp
        UpdateEnvironment.installer = null
        UpdateEnvironment.versionOverride = null
        resetPrefs()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    private fun resetPrefs() = runBlocking {
        // Checked "long ago", so the start-up check runs; nothing skipped or dismissed.
        DataStoreUpdatePrefs(context).edit { UpdatePrefsData() }
    }

    @Test
    fun bannerNotesInstall() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val banner = hasTestTag(UPDATE_BANNER_TAG)
        rule.waitUntilAtLeastOneExists(banner, 30_000)
        rule.onNode(hasText(str(R.string.update_banner_title, "9.9.9"))).assertExists()

        rule.onNode(hasText(str(R.string.update_whats_new)) and hasAnyAncestor(banner)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Faster everything"), 10_000)
        rule.onNode(hasText(str(R.string.update_installed_version, "0.0.5"))).assertExists()

        rule.onNode(hasTestTag(UPDATE_INSTALL_TAG)).performScrollTo().performClick()
        rule.waitUntil(30_000) { installer.installed.isNotEmpty() }
        val file = installer.installed.single()
        assertEquals(Releases.apkName(version, variant), file.name)
        assertArrayEquals(apk, file.readBytes())
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.update_installing)), 10_000)

        // The user backs out of the system dialog: Install is offered again.
        runBlocking { installer.results.emit(InstallResult.Cancelled) }
        rule.waitUntilAtLeastOneExists(hasTestTag(UPDATE_INSTALL_TAG), 10_000)
    }

    @Test
    fun skipHidesTheBannerForThatVersion() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val banner = hasTestTag(UPDATE_BANNER_TAG)
        rule.waitUntilAtLeastOneExists(banner, 30_000)
        rule.onNode(hasText(str(R.string.update_banner_update)) and hasAnyAncestor(banner)).performClick()
        // Update starts the download straight away; wait for it to reach the installer.
        rule.waitUntil(30_000) { installer.installed.isNotEmpty() }
        runBlocking { installer.results.emit(InstallResult.Cancelled) }
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.update_skip)), 10_000)
        rule.onNode(hasText(str(R.string.update_skip))).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { readSkipped() } == "9.9.9" }
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNode(banner).assertDoesNotExist()
    }

    private suspend fun readSkipped(): String? {
        var skipped: String? = null
        DataStoreUpdatePrefs(context).edit { skipped = it.skipped; it }
        return skipped
    }

    private class RecordingInstaller : ApkInstaller {
        val installed = CopyOnWriteArrayList<File>()
        override val results = MutableSharedFlow<InstallResult>()
        override fun canInstall() = true
        override suspend fun install(file: File) {
            installed += file
        }
    }
}
