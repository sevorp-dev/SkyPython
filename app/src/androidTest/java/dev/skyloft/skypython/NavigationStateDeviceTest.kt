package dev.skyloft.skypython

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.editor.EditorViewModel
import dev.skyloft.skypython.report.ErrorReporter
import dev.skyloft.skypython.settings.SettingsStore
import dev.skyloft.skypython.terminal.TERMINAL_TAG
import dev.skyloft.skypython.terminal.TerminalViewModel
import dev.skyloft.skypython.ui.nav.TopLevelDestination
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * State that must outlive a screen: a terminal screen brought back after the process died (fresh
 * view models, restored route) starts its program; the editor keeps cursor and undo history across
 * a tab switch and a rotation.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NavigationStateDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var dir: File? = null

    private fun str(@StringRes id: Int): String = rule.activity.getString(id)

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Before
    fun barOn() = runBlocking { SettingsStore(rule.activity).update { it.copy(bottomNavBar = true) } }

    @After
    fun cleanUp() {
        val d = dir ?: return
        val vm = editorVm()
        onMain { vm.closeFile(d) }
        rule.waitForIdle()
        d.deleteRecursively()
    }

    private fun editorVm() = onMain { ViewModelProvider(rule.activity)[EditorViewModel::class.java] }

    private fun terminalVm(key: String) = onMain { ViewModelProvider(rule.activity)[key, TerminalViewModel::class.java] }

    private fun editor(): CodeEditor {
        fun find(v: View): CodeEditor? = when (v) {
            is CodeEditor -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(rule.activity.window.decorView) ?: error("no code editor on screen")
    }

    private fun waitForEditor() {
        rule.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.editor_menu)), 20_000)
        rule.waitUntil(20_000) { onMain { runCatching { editor() }.isSuccess } }
    }

    private fun hideKeyboard() = rule.runOnUiThread {
        val window = rule.activity.window
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    private fun drawer(@StringRes item: Int) {
        rule.onNode(hasContentDescription(str(R.string.editor_menu))).performClick()
        rule.waitUntilAtLeastOneExists(hasText(str(item)), 10_000)
        rule.onNode(hasText(str(item))).performClick()
        rule.waitForIdle()
    }

    /**
     * As after process death: every view model is gone, the activity comes back with its saved
     * navigation state (the screen the user was on) and gets fresh view models.
     */
    private fun recreateWithFreshViewModels() {
        onMain { rule.activity.viewModelStore.clear() }
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    private fun assertSessionStarts(key: String) {
        val vm = terminalVm(key)
        rule.waitUntil(TIMEOUT_MS) { onMain { vm.state.value.session != null } }
        rule.waitUntilAtLeastOneExists(hasTestTag(TERMINAL_TAG), 10_000)
    }

    @Test
    fun terminalRestoredAfterProcessDeathStartsItsShell() {
        waitForEditor()
        drawer(R.string.title_terminal)
        rule.waitUntilAtLeastOneExists(hasTestTag(TERMINAL_TAG), TIMEOUT_MS)
        recreateWithFreshViewModels()
        assertSessionStarts("terminal-shell")
    }

    @Test
    fun interpreterRestoredAfterProcessDeathStartsPython() {
        waitForEditor()
        drawer(R.string.title_interpreter)
        rule.waitUntilAtLeastOneExists(hasTestTag(TERMINAL_TAG), TIMEOUT_MS)
        recreateWithFreshViewModels()
        assertSessionStarts("terminal-interpreter")
    }

    @Test
    fun runRestoredAfterProcessDeathGoesBackToTheEditor() {
        waitForEditor()
        rule.onNode(hasContentDescription(str(R.string.action_run))).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(TERMINAL_TAG), TIMEOUT_MS)
        recreateWithFreshViewModels()
        // No script to show any more: not an endless spinner, the editor.
        rule.waitUntilAtLeastOneExists(hasContentDescription(str(R.string.editor_menu)), 20_000)
        assertEquals(0, rule.onAllNodes(hasText(str(R.string.console_preparing_python))).fetchSemanticsNodes().size)
    }

    @Test
    fun editorKeepsCursorAndUndoAcrossTabSwitchAndRotation() {
        waitForEditor()
        val projects = File(rule.activity.filesDir, "projects").canonicalFile
        val d = File(projects, "statetest").apply { mkdirs() }.also { dir = it }
        val text = (1..80).joinToString("\n") { "line_$it = $it" } + "\n"
        val file = File(d, "keep.py").apply { writeText(text) }
        val vm = editorVm()
        onMain { vm.open(file) }
        rule.waitUntil(20_000) { onMain { editor().text.toString() == text } }

        onMain {
            val e = editor()
            e.setSelection(LINE, 0)
            e.insertText("typed", 5)
            e.hideSoftInput()
        }
        val edited = onMain { editor().text.toString() }
        assertTrue(edited.contains("typed"))
        hideKeyboard()

        // Another tab and back.
        clickBottomBar(TopLevelDestination.Terminal.tag)
        rule.waitUntilAtLeastOneExists(hasTestTag(TERMINAL_TAG), TIMEOUT_MS)
        hideKeyboard()
        clickBottomBar(TopLevelDestination.Editor.tag)
        waitForEditor()
        rule.waitUntil(10_000) { onMain { editor().text.toString() == edited } }
        assertEquals("cursor line after a tab switch", LINE, onMain { editor().cursor.leftLine })
        assertEquals("cursor column after a tab switch", 5, onMain { editor().cursor.leftColumn })

        // A rotation (the activity is recreated, the view models stay).
        rule.activityRule.scenario.recreate()
        waitForEditor()
        rule.waitUntil(10_000) { onMain { editor().text.toString() == edited } }
        assertEquals("cursor line after a rotation", LINE, onMain { editor().cursor.leftLine })
        // The scroll position is restored after the recreated editor lays out.
        rule.waitUntil(10_000) { onMain { editor().firstVisibleLine <= LINE && LINE <= editor().lastVisibleLine } }

        // The typing is still in the undo history.
        onMain { editor().undo() }
        rule.waitUntil(5_000) { onMain { editor().text.toString() == text } }
    }

    /**
     * The bottom bar hides while the keyboard is up, and the terminal opens the keyboard by itself,
     * so the bar can vanish between "it's there" and the click. Hide the keyboard and retry.
     */
    private fun clickBottomBar(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (true) {
            hideKeyboard()
            try {
                rule.waitUntilAtLeastOneExists(hasTestTag(tag), 5_000)
                rule.onNode(hasTestTag(tag)).performClick()
                return
            } catch (e: Throwable) {
                if (SystemClock.elapsedRealtime() > deadline) throw e
            }
        }
    }

    @Test
    fun problemBannerStaysAboveTheSymbolBar() {
        waitForEditor()
        hideKeyboard()
        ErrorReporter.report(ErrorReporter.Where.PYTHON, IllegalStateException("banner placement"))
        val action = hasText(str(R.string.report_banner_action))
        rule.waitUntilAtLeastOneExists(action, 10_000)
        val banner = rule.onNode(action).fetchSemanticsNode().boundsInWindow
        // The code view ends where the editor's bottom controls (status line, symbol bar) begin.
        val codeBottom = onMain {
            val e = editor()
            val at = IntArray(2)
            e.getLocationInWindow(at)
            at[1] + e.height
        }
        // The notice is app-wide: put it away so it does not show again over the next test's screens.
        ErrorReporter.hub.dismiss()
        assertTrue("banner bottom ${banner.bottom} over the symbol bar at $codeBottom", banner.bottom <= codeBottom)    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
        const val LINE = 60
    }
}
