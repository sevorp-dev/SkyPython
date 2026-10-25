package dev.skyloft.skypython.report

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.R
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.ui.theme.SkyPythonTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLDecoder

/** The Report screen's actions build the right intents; nothing is sent by the app itself. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReportScreenDeviceTest {

    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val launched = mutableListOf<Intent>()
    private val store = Reports.store(context)

    private val crash = ProblemReport(
        id = "device-test-crash",
        kind = ReportKind.Crash,
        timeMillis = System.currentTimeMillis(),
        thread = "main",
        screen = "editor",
        message = "java.lang.IllegalStateException: seeded",
        stackTrace = StackTraces.render(IllegalStateException("seeded", RuntimeException("seeded cause"))),
        appVersion = "test",
        log = listOf("I/Test: seeded"),
    )

    private fun show(reportId: String?) {
        rule.setContent {
            SkyPythonTheme {
                ReportScreen(reportId, EditorSettings(), onBack = {}, launch = { _, intent -> launched += intent; true })
            }
        }
        rule.waitUntil(20_000) { Build.MODEL in rule.reportText() }
    }

    @After fun cleanUp() {
        store.dir.deleteRecursively()
    }

    @Test fun shareSendsTheWholeReport() {
        store.add(crash)
        show(crash.id)
        rule.onNode(hasTestTag(REPORT_DESCRIPTION_TAG)).performTextInput("Tapped Run twice")
        rule.onNode(hasText(context.getString(R.string.report_share))).performScrollTo().assertIsEnabled().performClick()
        // The report file is written off the main thread, then the chooser starts.
        // The share file is written off the main thread; slow emulators need a moment.
        rule.waitUntil(20_000) { launched.isNotEmpty() }

        val chooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val text = if (stream != null) {
            // Long: a .txt through the FileProvider.
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            context.contentResolver.openInputStream(stream)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        }
        assertTrue(text, "Tapped Run twice" in text)
        assertTrue(text, "Caused by: java.lang.RuntimeException: seeded cause" in text)
        assertTrue(text, "- Model: ${Build.MODEL}" in text)
        assertTrue(text, "I/Test: seeded" in text)
    }

    @Test fun githubIssueLinkIsPrefilledAndTheFullReportIsCopied() {
        store.add(crash)
        show(crash.id)
        rule.onNode(hasText(context.getString(R.string.report_open_issue))).performScrollTo().performClick()
        rule.waitForIdle()

        val view = launched.single()
        assertEquals(Intent.ACTION_VIEW, view.action)
        val url = view.dataString!!
        assertTrue(url, url.startsWith("${IssueUrl.NEW_ISSUE}?template=bug_report.yml&labels=bug&title="))
        assertTrue(url.length <= IssueUrl.MAX_URL_LENGTH)
        assertTrue(url, "java.lang.IllegalStateException: seeded" in URLDecoder.decode(url, "UTF-8"))

        val clip = rule.runOnIdle {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        }
        assertTrue(clip, "## App log" in clip && "- Model: ${Build.MODEL}" in clip)
    }

    @Test fun deviceInfoCanBeLeftOut() {
        show(null)
        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(5_000) { "## Device" !in rule.reportText() }
        val text = rule.reportText()
        assertTrue(text, "Kind: Manual" in text && "## Error" in text)
        assertTrue(text, "- Model:" !in text)
        File(context.cacheDir, "reports").deleteRecursively()
    }
}
