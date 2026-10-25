package dev.skyloft.skypython.editor

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.MainActivity
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Code navigation on the real screen with the real language server: go to definition in the
 * file, into a second project file and into library code (read-only), the outline sheet, and
 * the parameter hint for `print(`. Works in projects/navtest/, removed afterwards.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorNavigationDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var dir: File
    private lateinit var main: File
    private lateinit var helper: File

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun editor(): CodeEditor {
        fun find(v: View): CodeEditor? = when (v) {
            is CodeEditor -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(rule.activity.window.decorView) ?: error("no code editor on screen")
    }

    private fun viewModel() = onMain { ViewModelProvider(rule.activity)[EditorViewModel::class.java] }

    @Before
    fun openMain() {
        rule.waitUntilAtLeastOneExists(hasText("hello.py", substring = true), 20_000)
        val projects = File(rule.activity.filesDir, "projects").canonicalFile
        dir = File(projects, "navtest").apply { mkdirs() }
        helper = File(dir, "helper.py").apply { writeText(HELPER) }
        main = File(dir, "main.py").apply { writeText(MAIN) }
        val vm = viewModel()
        onMain { vm.open(main) }
        rule.waitUntil(20_000) { onMain { editor().text.toString() == MAIN } }
    }

    @After
    fun cleanUp() {
        val vm = viewModel()
        onMain { vm.closeFile(dir) }
        rule.waitForIdle()
        dir.deleteRecursively()
    }

    private fun cursorAt(line: Int, column: Int) = onMain {
        val e = editor()
        e.requestFocus()
        e.setSelection(line, column)
    }

    private fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun selected(): String = onMain {
        val c = editor().cursor
        if (!c.isSelected) "" else editor().text.subContent(c.leftLine, c.leftColumn, c.rightLine, c.rightColumn).toString()
    }

    /** F12 until the server (cold on the first test) answers and the selection lands on [name]. */
    private fun goToDefinition(name: String, landed: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + SERVER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            key(KeyEvent.KEYCODE_F12)
            val until = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < until) {
                if (selected() == name && landed()) return
                Thread.sleep(200)
            }
        }
        error("did not land on $name")
    }

    @Test
    fun definitionInTheSameFile() {
        cursorAt(MAIN_CALL_LINE, 1) // greet("x")
        goToDefinition("greet") { onMain { editor().cursor.leftLine } == 3 }
        assertEquals(4, onMain { editor().cursor.leftColumn })
    }

    @Test
    fun definitionInAnotherProjectFileOpensItEditable() {
        cursorAt(4, 12) // return shout(who)
        goToDefinition("shout") { onMain { editor().text.toString() } == HELPER }
        assertEquals(2, onMain { editor().cursor.leftLine })
        assertTrue("project files stay editable", onMain { editor().isEditable })
        rule.onNodeWithText("helper.py", substring = true).assertExists()
    }

    @Test
    fun definitionInTheStandardLibraryOpensReadOnly() {
        cursorAt(MAIN_PRINT_LINE, 1) // print(...)
        goToDefinition("print") { onMain { editor().text.toString() }.contains("def print(") }
        assertFalse("library code is read-only", onMain { editor().isEditable })
        rule.waitUntilAtLeastOneExists(hasText("Read-only"), 5_000)
        rule.waitUntilAtLeastOneExists(hasText("builtins.pyi", substring = true), 5_000)
    }

    @Test
    fun outlineListsSymbolsAndJumps() {
        cursorAt(0, 0)
        val deadline = SystemClock.uptimeMillis() + SERVER_TIMEOUT_MS
        while (rule.onAllNodesWithTagCount("outline_sheet") == 0 || !hasRow("size")) {
            check(SystemClock.uptimeMillis() < deadline) { "outline never showed" }
            if (rule.onAllNodesWithTagCount("outline_sheet") == 0) key(KeyEvent.KEYCODE_O, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            Thread.sleep(1_000)
        }
        rule.onNodeWithText("greet").assertExists()
        rule.onNodeWithText("Box").assertExists()
        rule.onNodeWithText("size").performClick()
        rule.waitUntil(10_000) { selected() == "size" }
        assertEquals(9, onMain { editor().cursor.leftLine })
    }

    @Test
    fun signatureHintAppearsForPrint() {
        onMain {
            val e = editor()
            e.requestFocus()
            val last = e.text.lineCount - 1
            e.setSelection(last, e.text.getColumnCount(last))
            e.insertText("\nprint(", 7)
        }
        rule.waitUntil(SERVER_TIMEOUT_MS) {
            rule.onAllNodesWithTagCount("signature_hint") > 0 ||
                run {
                    // The server may still be starting: nudge the cursor to ask again.
                    onMain {
                        val e = editor()
                        val l = e.cursor.leftLine
                        val c = e.cursor.leftColumn
                        e.setSelection(l, c - 1)
                        e.setSelection(l, c)
                    }
                    Thread.sleep(1_000)
                    false
                }
        }
        rule.onNodeWithTag("signature_hint").assertTextContains("print(*values", substring = true)
        // `)` closes the call: the hint goes away.
        onMain { editor().insertText(")", 1) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount("signature_hint") == 0 }
    }

    private fun hasRow(name: String) = rule.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private companion object {
        const val SERVER_TIMEOUT_MS = 180_000L
        const val HELPER = "LOUD = True\n\ndef shout(text):\n    return text.upper()\n"
        const val MAIN = "from helper import shout\n" +   // 0
            "\n" +                                       // 1
            "\n" +                                       // 2
            "def greet(who):\n" +                        // 3
            "    return shout(who)\n" +                  // 4
            "\n" +                                       // 5
            "\n" +                                       // 6
            "class Box:\n" +                             // 7
            "    \"\"\"A box.\"\"\"\n" +                 // 8
            "    def size(self):\n" +                    // 9
            "        return 1\n" +                       // 10
            "\n" +                                       // 11
            "\n" +                                       // 12
            "greet(\"x\")\n" +                           // 13
            "print(Box().size())\n"                      // 14
        const val MAIN_CALL_LINE = 13
        const val MAIN_PRINT_LINE = 14
    }
}
