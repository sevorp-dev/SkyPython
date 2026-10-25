package dev.skyloft.skypython.terminal

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.R
import dev.skyloft.skypython.pip.LookupResult
import dev.skyloft.skypython.pip.PackageDetails
import dev.skyloft.skypython.pip.PackageIndex
import dev.skyloft.skypython.pip.PipManager
import dev.skyloft.skypython.pip.PipPackage
import dev.skyloft.skypython.pip.PipViewModel
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.ui.theme.SkyPythonTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ▶ Run of a script that imports a module that isn't installed: the finished bar offers
 * "Install cv2 (opencv-python)", which runs pip with a live log and then offers Run again.
 * Real Python on a terminal; pip is a fake, so it works offline.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class InstallMissingDeviceTest {

    @get:Rule val rule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val dir = File(app.filesDir, "projects/install-missing-test").apply { mkdirs() }
    private var vm: TerminalViewModel? = null

    private class FakePip : PipManager {
        val runs = mutableListOf<String>()
        override fun install(spec: String): Flow<String> = flow {
            runs += "install $spec"
            emit("Collecting $spec\n")
            emit("Successfully installed $spec-4.10.0\n")
        }
        override fun upgrade(name: String): Flow<String> = flow { }
        override fun installRequirements(file: File): Flow<String> = flow { }
        override suspend fun uninstall(name: String) = Result.success(Unit)
        override suspend fun listInstalled() = Result.success(emptyList<PipPackage>())
        override suspend fun listOutdated() = Result.success(emptyMap<String, String>())
        override suspend fun show(name: String): Result<PackageDetails> = Result.failure(UnsupportedOperationException())
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { vm?.kill() }
        dir.deleteRecursively()
    }

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)

    /** Saved where `adb pull` can fetch it (the app's external files folder). */
    private fun screenshot(name: String) {
        val out = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun aMissingModuleCanBeInstalledFromTheRunScreen() {
        val script = File(dir, "cam.py").apply { writeText("import cv2\nprint(cv2.__version__)\n") }
        val pipFake = FakePip()
        val pip = PipViewModel(pipFake, PackageIndex { LookupResult.Offline("offline") }) { emptyList() }
        val installer = PythonInstaller(app)
        lateinit var run: TerminalViewModel
        instrumentation.runOnMainSync {
            run = TerminalViewModel(TerminalKind.Run, File(app.filesDir, "projects"), app, installer::ensureInstalled)
            vm = run
            run.run(script)
        }
        rule.setContent { SkyPythonTheme { TerminalScreen(viewModel = run, onBack = {}, onUseSimpleConsole = {}, pip = pip) } }

        val label = str(R.string.run_install_missing_as, "cv2", "opencv-python")
        rule.waitUntilAtLeastOneExists(hasText(label), 120_000)
        rule.onNode(hasTestTag(INSTALL_MISSING_TAG)).assertIsDisplayed()
        rule.waitForIdle()
        screenshot("install-missing-action")

        rule.onNode(hasTestTag(INSTALL_MISSING_TAG)).performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.pip_installed_spec, "opencv-python")), 10_000)
        rule.onNode(hasText("Successfully installed", substring = true)).assertExists()
        // Run again is offered in the sheet too (besides the finished bar's).
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(str(R.string.run_again))).fetchSemanticsNodes().size >= 2 }
        rule.waitForIdle()
        screenshot("install-missing-sheet")
        assertEquals(listOf("install opencv-python"), pipFake.runs)
    }
}
