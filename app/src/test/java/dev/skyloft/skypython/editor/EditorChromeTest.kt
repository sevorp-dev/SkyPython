package dev.skyloft.skypython.editor

import dev.skyloft.skypython.lang.Diagnostic
import dev.skyloft.skypython.lang.Severity
import dev.skyloft.skypython.settings.EditorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditorChromeTest {

    @Test fun `clean tab shows the bare label`() {
        assertEquals("main.py", tabTitle("main.py", dirty = false))
    }

    @Test fun `modified tab gets a star suffix`() {
        assertEquals("main.py*", tabTitle("main.py", dirty = true))
    }

    @Test fun `subtitle of a doc in the projects root is Projects`() {
        val root = File(System.getProperty("java.io.tmpdir"), "sky-sub/projects")
        val doc = OpenDocument(id = "x", source = DocSource.Local(File(root, "main.py")), name = "main.py", text = "", dirty = false)
        assertEquals("Projects", docSubtitle(doc, root))
    }

    @Test fun `subtitle of a doc in a project folder is relative to Projects`() {
        val root = File(System.getProperty("java.io.tmpdir"), "sky-sub/projects")
        val doc = OpenDocument(id = "x", source = DocSource.Local(File(root, "demo/sub/main.py")), name = "main.py", text = "", dirty = false)
        assertEquals("Projects/demo/sub", docSubtitle(doc, root))
    }

    @Test fun `subtitle of a local doc outside projects is its folder path`() {
        val root = File(System.getProperty("java.io.tmpdir"), "sky-sub/projects")
        val file = File(System.getProperty("java.io.tmpdir"), "elsewhere/main.py")
        val doc = OpenDocument(id = "x", source = DocSource.Local(file), name = "main.py", text = "", dirty = false)
        assertEquals(DocSource.Local(file).file.parentFile!!.path, docSubtitle(doc, root))
    }

    @Test fun `settings map to editor view config`() {
        val config = EditorViewConfig.from(EditorSettings(fontSizeSp = 18, wordWrap = false, lineNumbers = false, keepScreenOn = true))
        assertEquals(EditorViewConfig(textSizeSp = 18f, wordWrap = false, lineNumbers = false, keepScreenOn = true), config)
    }

    @Test fun `defaults are word wrap, line numbers and 15sp`() {
        val config = EditorViewConfig.from(EditorSettings())
        assertEquals(15f, config.textSizeSp)
        assertTrue(config.wordWrap)
        assertTrue(config.lineNumbers)
        assertFalse(config.keepScreenOn)
        assertTrue(config.codeCompletion)
    }

    @Test fun `problem label shows the 1-based line and the first message line`() {
        val d = Diagnostic(3, 4, 3, 5, Severity.ERROR, "SyntaxError: '(' was never closed\nmore")
        assertEquals("Line 3: SyntaxError: '(' was never closed", problemLabel(d))
    }

    @Test fun `code completion setting reaches the editor`() {
        assertFalse(EditorViewConfig.from(EditorSettings(codeCompletion = false)).codeCompletion)
    }

    @Test fun `font size is clamped to the settings range`() {
        assertEquals(12f, EditorViewConfig.from(EditorSettings(fontSizeSp = 2)).textSizeSp)
        assertEquals(24f, EditorViewConfig.from(EditorSettings(fontSizeSp = 99)).textSizeSp)
    }

    @Test fun `symbol bar has the full symbol list`() {
        val labels = SYMBOL_KEYS.map { it.first }
        assertEquals(
            listOf("Tab", ":", ";", "'", "\"", "#", "(", ")", "[", "]", "{", "}", "=", ".", ",", "_", "+", "-", "*", "/", "<", ">", "\\", "|"),
            labels,
        )
    }

    @Test fun `Tab inserts four spaces and symbols insert themselves`() {
        val keys = SYMBOL_KEYS.toMap()
        assertEquals("    ", keys["Tab"])
        SYMBOL_KEYS.drop(1).forEach { (label, text) -> assertEquals(label, text) }
    }
}
