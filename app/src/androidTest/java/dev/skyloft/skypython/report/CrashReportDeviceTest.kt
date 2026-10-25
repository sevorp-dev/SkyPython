package dev.skyloft.skypython.report

import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.MainActivity
import dev.skyloft.skypython.R
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The text of the Report screen's full report. */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.reportText(): String =
    onAllNodes(hasTestTag(REPORT_TEXT_TAG)).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

/**
 * A real crash (debug-only [dev.skyloft.skypython.debugtools.CrashTestActivity], in its own
 * process) is saved by the crash handler; the next start asks to send it and the Report screen
 * shows it with the device. Also: "Report a problem" from the drawer.
 *
 * The activity is started by hand after the crash folder is cleaned, so no other saved crash
 * can pop the dialog up.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CrashReportDeviceTest {

    @get:Rule val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val crashDir = File(context.filesDir, "crash")

    private fun str(@StringRes id: Int): String = context.getString(id)

    @Before fun clean() {
        crashDir.deleteRecursively()
    }

    @After fun cleanUp() {
        crashDir.deleteRecursively()
    }

    @Test fun crashIsSavedAndOfferedOnTheNextStart() {
        val crash = Intent()
            .setClassName(context, "dev.skyloft.skypython.debugtools.CrashTestActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assumeTrue("debug build only", context.packageManager.resolveActivity(crash, 0) != null)
        context.startActivity(crash)

        val deadline = System.currentTimeMillis() + 20_000
        while (crashDir.listFiles().orEmpty().none { it.name.endsWith(".json") }) {
            assertTrue("no crash report saved", System.currentTimeMillis() < deadline)
            Thread.sleep(200)
        }
        val saved = Reports.store(context).list().single()
        assertTrue(saved.stackTrace.orEmpty(), "Caused by: java.io.IOException: Test cause" in saved.stackTrace.orEmpty())
        assertTrue(saved.thread, saved.thread == "main")

        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntilAtLeastOneExists(hasText(str(R.string.crash_dialog_title)), 30_000)
            rule.onNode(hasText(str(R.string.crash_dialog_send))).performClick()
            rule.waitUntil(20_000) { "Test crash from CrashTestActivity" in rule.reportText() && Build.MODEL in rule.reportText() }
            val text = rule.reportText()
            assertTrue(text, "Kind: Crash" in text)
            assertTrue(text, "Caused by: java.io.IOException: Test cause" in text)
            assertTrue(text, "- Model: ${Build.MODEL}" in text)
            assertTrue(text, "## Mobile operator" in text || "## Network" in text)
            // Asked once: the report stays saved, but the question is not repeated.
            rule.waitUntil(5_000) { Reports.store(context).unprompted() == null }
            assertTrue(Reports.store(context).list().size == 1)
        }
    }

    @Test fun aSurvivedErrorShowsABannerThatOpensItsReport() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.editor_menu)), 30_000)
            val problem = "no /dev/ptmx " + System.nanoTime() // unique: never swallowed as a repeat
            ErrorReporter.report(ErrorReporter.Where.TERMINAL, java.io.IOException(problem))
            val banner = context.getString(R.string.report_banner, str(R.string.report_where_terminal))
            rule.waitUntilAtLeastOneExists(hasText(banner), 10_000)
            rule.onNode(hasText(str(R.string.report_banner_action))).performClick()
            rule.waitUntil(20_000) { problem in rule.reportText() && Build.MODEL in rule.reportText() }
            val text = rule.reportText()
            assertTrue(text, "Kind: Error" in text && "Where: terminal" in text)
            assertTrue(text, "java.io.IOException: $problem" in text)
        }
    }

    @Test fun reportAProblemFromTheDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.editor_menu)), 30_000)
            rule.onNode(hasContentDescription(str(R.string.editor_menu))).performClick()
            // The drawer scrolls: on a small screen (CI's default emulator) "Report a problem" is
            // below the fold, and a click on an off-screen node lands nowhere.
            rule.waitUntilAtLeastOneExists(hasText(str(R.string.report_problem)), 5_000)
            rule.onNode(hasText(str(R.string.report_problem))).performScrollTo().performClick()
            rule.waitUntilAtLeastOneExists(hasTestTag(REPORT_DESCRIPTION_TAG), 10_000)
            rule.waitUntil(20_000) { Build.MODEL in rule.reportText() }
            val text = rule.reportText()
            assertTrue(text, "Kind: Manual" in text)
            assertTrue(text, "Screen: editor" in text)
            assertTrue(text, "## App log" in text)
        }
    }
}
