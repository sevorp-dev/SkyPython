package dev.skyloft.skypython.editor

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.MainActivity
import dev.skyloft.skypython.settings.EditorSettings
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's Sora wiring on the real screen: what the soft keyboard is told (no suggestions,
 * no autocorrect by default) and the line commands with one undo step each. hello.py is
 * restored afterwards.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorToolsDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private var original: String? = null

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
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

    @Before
    fun waitForEditor() {
        rule.waitUntilAtLeastOneExists(hasText("hello.py", substring = true), 20_000)
        rule.waitUntil(20_000) { onMain { editor().isEditable } }
        original = onMain { editor().text.toString() }
    }

    /** What the soft keyboard is told, as if no hardware keyboard were attached (the emulator has one). */
    private fun editorInfo(): EditorInfo = onMain {
        val view = editor()
        view.setDisableSoftKbdIfHardKbdAvailable(false)
        try {
            EditorInfo().also { view.onCreateInputConnection(it) }
        } finally {
            view.setDisableSoftKbdIfHardKbdAvailable(true)
        }
    }

    @Test
    fun keyboardGetsNoSuggestionsByDefault() {
        onMain { editor().applyConfig(EditorViewConfig.from(EditorSettings())) }
        val type = editorInfo().inputType
        assertTrue(type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, type and InputType.TYPE_MASK_VARIATION)
        assertEquals(0, type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    }

    @Test
    fun keyboardSuggestionsSettingRestoresPlainText() {
        onMain { editor().applyConfig(EditorViewConfig.from(EditorSettings(keyboardSuggestions = true))) }
        try {
            assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, editorInfo().inputType)
        } finally {
            onMain { editor().applyConfig(EditorViewConfig.from(EditorSettings())) }
        }
    }

    @Test
    fun lineCommandsEditTheViewAndUndoInOneStep() {
        onMain {
            val view = editor()
            val controller = EditorController().apply { this.view = view }
            val content = view.text
            val last = content.lineCount - 1
            view.setSelection(last, content.getColumnCount(last))
            view.insertText("\na = 1\nb = 2", "\na = 1\nb = 2".length)
            val start = view.text.toString()
            val base = start.removeSuffix("a = 1\nb = 2")

            controller.toggleComment()
            assertEquals(base + "a = 1\n# b = 2", view.text.toString())
            controller.moveLineUp()
            assertEquals(base + "# b = 2\na = 1", view.text.toString())
            controller.duplicateLine()
            assertEquals(base + "# b = 2\n# b = 2\na = 1", view.text.toString())

            view.undo()
            view.undo()
            view.undo()
            assertEquals(start, view.text.toString())
        }
    }

    @After
    fun restoreHelloPy() {
        val before = original ?: return
        onMain {
            val content = editor().text
            val last = content.lineCount - 1
            content.replace(0, 0, last, content.getColumnCount(last), before)
        }
        rule.waitForIdle()
        Thread.sleep(1_000) // the editor reports edits after a 300 ms pause; ON_PAUSE then saves
    }
}
