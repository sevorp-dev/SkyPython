package dev.skyloft.skypython

import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.terminal.TERMINAL_TAG
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * First launch: hello.py opens, ▶ Run executes it on a terminal, input() gets typed keys, and
 * the output can be copied and saved.
 *
 * The terminal is an Android view, not Compose text: its semantics text is what is on the
 * screen (the same rows the user sees), and keys go to it as key events, like a keyboard's.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppEndToEndTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** UI text in the device's app language (the program's own output stays English). */
    private fun str(@StringRes id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private fun terminalShows(text: String): SemanticsMatcher =
        hasTestTag(TERMINAL_TAG) and hasText(text, substring = true)

    @Test
    fun runHelloPyAndAnswerInput() {
        rule.waitUntilAtLeastOneExists(hasText("hello.py", substring = true), 20_000)

        rule.onNode(hasContentDescription(str(R.string.action_run))).performClick()

        // First run unpacks the stdlib, so allow time.
        rule.waitUntilAtLeastOneExists(terminalShows("What's your name?"), 90_000)

        // Typed into the focused terminal view (as a hardware keyboard would); Enter ends the line.
        instrumentation.sendStringSync("Sky\n")

        rule.waitUntilAtLeastOneExists(terminalShows("Hello, Sky!"), 20_000)
        rule.waitUntilAtLeastOneExists(terminalShows(str(R.string.console_exit_footer, 0)), 20_000)

        // Copy all: the whole transcript, footer included, goes to the clipboard.
        rule.onNode(hasContentDescription(str(R.string.output_copy_all))).performClick()
        rule.waitForIdle()
        val clip = rule.runOnUiThread {
            val cm = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(rule.activity)?.toString().orEmpty()
        }
        assertTrue(clip, "What's your name?" in clip && "Hello, Sky!" in clip)
        assertTrue(clip, str(R.string.console_exit_footer, 0) in clip)

        // Save output: hello_output.txt (or -2, -3… if earlier runs saved one) next to hello.py.
        val projects = File(rule.activity.filesDir, "projects")
        val before = projects.listFiles().orEmpty().map { it.name }.toSet()
        rule.onNode(hasContentDescription(str(R.string.output_more))).performClick()
        rule.onNode(hasText(str(R.string.output_save))).performClick()
        rule.waitUntil(10_000) {
            projects.listFiles().orEmpty().any { it.name.startsWith("hello_output") && it.name !in before }
        }
        val saved = projects.listFiles()!!.first { it.name.startsWith("hello_output") && it.name !in before }
        val text = saved.readText()
        assertTrue(text, "Hello, Sky!" in text)
        saved.delete()
    }
}
