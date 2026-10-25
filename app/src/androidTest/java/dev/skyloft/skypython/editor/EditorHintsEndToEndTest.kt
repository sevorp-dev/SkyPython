package dev.skyloft.skypython.editor

import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.MainActivity
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor end to end: an error typed into hello.py shows in the problem line, and the
 * completion popup offers `json` for `import js`. hello.py is restored afterwards.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorHintsEndToEndTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private var original: String? = null

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun editor(): CodeEditor {
        fun find(v: View): CodeEditor? = when (v) {
            is CodeEditor -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(rule.activity.window.decorView) ?: error("no code editor on screen")
    }

    private fun completion(): EditorAutoCompletion = editor().getComponent(EditorAutoCompletion::class.java)

    /** Labels of the proposals in the completion popup's list, in display order (main thread). */
    private fun proposals(c: EditorAutoCompletion): List<String> {
        fun list(v: View): AdapterView<*>? = when (v) {
            is AdapterView<*> -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { list(v.getChildAt(it)) }
            else -> null
        }
        val adapter = c.popup.contentView?.let(::list)?.adapter ?: return emptyList()
        return (0 until adapter.count).map { (adapter.getItem(it) as CompletionItem).label.toString() }
    }

    /** Types [text] at the end of the document, as the keyboard would (one edit). */
    private fun typeAtEnd(text: String) = onMain {
        val e = editor()
        val content = e.text
        val last = content.lineCount - 1
        e.setSelection(last, content.getColumnCount(last))
        e.insertText(text, text.length)
    }

    @Test
    fun errorShowsInProblemLineAndCompletionOffersJson() {
        rule.waitUntilAtLeastOneExists(hasText("hello.py", substring = true), 20_000)
        onMain { original = editor().text.toString() }

        typeAtEnd("\n")
        typeAtEnd("print(undefined_thing)")
        // First start unpacks Python and the server bundle: allow time.
        rule.waitUntilAtLeastOneExists(hasText("undefined name 'undefined_thing'", substring = true), 180_000)

        typeAtEnd("\n")
        typeAtEnd("import js")
        // Wait for the popup to be on screen offering `json`. (Not `!isCompletionInProgress`:
        // Sora counts a showing popup as "in progress", so that never turns false while shown.)
        rule.waitUntil(30_000) {
            var offered = false
            onMain {
                val c = completion()
                offered = c.isShowing && "json" in proposals(c)
            }
            offered
        }
        // Commit `json` by its position, looked up in the same main-thread step as the commit.
        onMain {
            val c = completion()
            val index = proposals(c).indexOf("json")
            assertTrue("json no longer offered", index >= 0 && c.select(index))
        }
        var text = ""
        onMain { text = editor().text.toString() }
        assertTrue(text, text.trimEnd().endsWith("import json"))
    }

    @After
    fun restoreHelloPy() {
        val before = original ?: return
        onMain {
            val content = editor().text
            val last = content.lineCount - 1
            // A normal edit (not setText), so the restored text is what gets saved.
            content.replace(0, 0, last, content.getColumnCount(last), before)
        }
        rule.waitForIdle()
        Thread.sleep(1_000) // the editor reports edits after a 300 ms pause; ON_PAUSE then saves
    }
}
