package dev.skyloft.skypython.pip

import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.R
import dev.skyloft.skypython.ui.theme.SkyPythonTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The Pip screen against a fake pip and a fake PyPI (no Python, no network). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PipScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private class FakePip : PipManager {
        val runs = mutableListOf<String>()
        var installed = listOf(PipPackage("six", "1.16.0"))
        private fun run(what: String): Flow<String> = flow {
            runs += what
            emit("Collecting ${what.substringAfter(' ')}\n")
            emit("Successfully installed\n")
        }
        override fun install(spec: String) = run("install $spec")
        override fun upgrade(name: String) = run("upgrade $name")
        override fun installRequirements(file: File) = run("requirements ${file.name}")
        override suspend fun uninstall(name: String) = Result.success(Unit)
        override suspend fun listInstalled() = Result.success(installed)
        override suspend fun listOutdated() = Result.success(mapOf("six" to "1.17.0"))
        override suspend fun show(name: String) =
            Result.success(PackageDetails(name, "1.16.0", "Python 2 and 3 compatibility", null, "MIT", emptyList(), emptyList()))
    }

    private val pip = FakePip()

    private fun show(index: PackageIndex = PackageIndex { LookupResult.Offline("offline") }): PipViewModel {
        val vm = PipViewModel(pip, index) { emptyList() }
        rule.setContent { SkyPythonTheme { PipScreen(vm, onBack = {}) } }
        return vm
    }

    @Test
    fun popularPackageInstallsWithALiveLog() {
        show()
        rule.onNode(hasText("requests")).assertExists()
        // "requests" is the first popular entry.
        rule.onAllNodes(hasText(str(R.string.pip_install))).onFirst().performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.pip_installed_spec, "requests")), 5_000)
        rule.onNode(hasText("Collecting requests", substring = true)).assertExists()
        assertEquals(listOf("install requests"), pip.runs)
    }

    @Test
    fun searchShowsTheProjectAndItsWheelSupport() {
        show(
            PackageIndex { name ->
                LookupResult.Found(PyPiProject(name, "1.17.0", "Py2/3 compatibility", null, "MIT", WheelSupport.Pure))
            },
        )
        rule.onNode(hasSetTextAction()).performTextInput("six")
        rule.onNode(hasText(str(R.string.pip_search_action))).performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.pip_wheel_pure)), 5_000)
        rule.onNode(hasText(str(R.string.pip_latest_version, "1.17.0"))).assertExists()
    }

    @Test
    fun aSearchResultShowsAtTheTopEvenWhenTheListWasScrolled() {
        show(
            PackageIndex { name ->
                LookupResult.Found(PyPiProject(name, "1.17.0", "Py2/3 compatibility", null, "MIT", WheelSupport.Pure))
            },
        )
        // The popular list was scrolled (or the result arrives while the first rows are anchored):
        // the result card goes in above them and must still be in view.
        rule.onNode(hasScrollAction()).performScrollToIndex(6)
        rule.onNode(hasSetTextAction()).performTextInput("six")
        rule.onNode(hasText(str(R.string.pip_search_action))).performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.pip_wheel_pure)), 5_000)
        rule.onNode(hasText(str(R.string.pip_wheel_pure))).assertIsDisplayed()
    }

    @Test
    fun offlineSearchSaysSo() {
        show()
        rule.onNode(hasSetTextAction()).performTextInput("six")
        rule.onNode(hasText(str(R.string.pip_search_action))).performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.pip_result_offline)), 5_000)
    }

    @Test
    fun installedTabOffersTheUpgrade() {
        show()
        rule.onNode(hasText(str(R.string.pip_tab_installed))).performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.pip_update_available, "1.17.0")), 5_000)
        rule.onNode(hasText(str(R.string.pip_upgrade))).performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(R.string.pip_done_upgrade, "six")), 5_000)
        assertEquals(listOf("upgrade six"), pip.runs)
    }
}
