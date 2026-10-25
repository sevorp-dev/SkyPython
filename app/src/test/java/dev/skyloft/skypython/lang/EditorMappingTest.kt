package dev.skyloft.skypython.lang

import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorMappingTest {

    private val lines = listOf("x = (", "", "print(undefined_thing)", "😀 = 1")
    private fun range(d: Diagnostic) = DiagnosticsMapping.toEditorRange(d, lines.size) { lines[it].length }
    private fun diag(line: Int, col: Int, endLine: Int, endCol: Int, severity: Severity = Severity.ERROR) =
        Diagnostic(line, col, endLine, endCol, severity, "m")

    @Test
    fun serverLinesAreOneBasedEditorLinesZeroBased() {
        assertEquals(EditorRange(0, 4, 0, 5), range(diag(1, 4, 1, 5)))
        assertEquals(EditorRange(2, 6, 2, 21), range(diag(3, 6, 3, 21)))
    }

    @Test
    fun columnsAreUtf16AndPassThroughUnchanged() {
        // 😀 is two UTF-16 units: the server already counts it as 2.
        assertEquals(EditorRange(3, 0, 3, 2), range(diag(4, 0, 4, 2)))
    }

    @Test
    fun positionsPastTheTextAreClamped() {
        assertEquals(EditorRange(0, 4, 0, 5), range(diag(1, 4, 1, 99)))
        // A line past the end (the file shrank meanwhile) goes to the last line.
        assertEquals(EditorRange(3, 1, 3, 2), range(diag(9, 1, 9, 2)))
        assertEquals(EditorRange(0, 0, 0, 1), range(diag(0, -3, 0, -1)))
    }

    @Test
    fun emptyRangesGetOneCharacterSoTheSquiggleShows() {
        assertEquals(EditorRange(0, 2, 0, 3), range(diag(1, 2, 1, 2)))
        // At the end of a line: the last character.
        assertEquals(EditorRange(0, 4, 0, 5), range(diag(1, 5, 1, 5)))
        // An empty line has nothing to underline: stays empty.
        assertEquals(EditorRange(1, 0, 1, 0), range(diag(2, 0, 2, 0)))
    }

    @Test
    fun endBeforeStartIsFixed() {
        assertEquals(EditorRange(2, 6, 2, 7), range(diag(3, 6, 2, 0)))
    }

    @Test
    fun firstErrorPrefersErrorsOverEarlierWarnings() {
        val w = diag(1, 0, 1, 1, Severity.WARNING)
        val e1 = diag(5, 0, 5, 1)
        val e2 = diag(3, 0, 3, 1)
        assertEquals(e2, DiagnosticsMapping.firstError(listOf(w, e1, e2)))
        assertNull(DiagnosticsMapping.firstError(listOf(w)))
        assertNull(DiagnosticsMapping.firstError(emptyList()))
    }

    @Test
    fun severityParsesLeniently() {
        assertEquals(Severity.ERROR, Severity.parse("error"))
        assertEquals(Severity.WARNING, Severity.parse("warning"))
        assertEquals(Severity.WARNING, Severity.parse("something new"))
    }

    @Test
    fun proposalReplacesTheTypedPrefix() {
        val p = CompletionMapping.proposal(Completion("json", "on", "module"))
        assertEquals(2, p.prefixLength)
        assertEquals("json", p.commitText)
        assertEquals(CompletionItemKind.Module, p.kind)
        // Case-insensitive match: "Up" typed, "upper" offered: the whole typed word is replaced.
        assertEquals(2, CompletionMapping.proposal(Completion("upper", "per", "function")).prefixLength)
        // Nothing typed yet (right after a dot).
        assertEquals(0, CompletionMapping.proposal(Completion("upper", "upper", "function")).prefixLength)
    }

    @Test
    fun kindsMapToSoraKinds() {
        assertEquals(CompletionItemKind.Class, CompletionMapping.kind("class"))
        assertEquals(CompletionItemKind.Function, CompletionMapping.kind("function"))
        assertEquals(CompletionItemKind.Keyword, CompletionMapping.kind("keyword"))
        assertEquals(CompletionItemKind.Variable, CompletionMapping.kind("instance"))
        assertEquals(CompletionItemKind.Variable, CompletionMapping.kind("statement"))
        assertEquals(CompletionItemKind.Property, CompletionMapping.kind("property"))
        assertEquals(CompletionItemKind.File, CompletionMapping.kind("path"))
        assertEquals(CompletionItemKind.Text, CompletionMapping.kind("???"))
    }

    @Test
    fun completionTriggersAfterIdentifierCharsAndDots() {
        assertTrue(CompletionMapping.shouldComplete("import js", 9))
        assertTrue(CompletionMapping.shouldComplete("\"abc\".", 6))
        assertTrue(CompletionMapping.shouldComplete("x = привет", 10))
        assertFalse(CompletionMapping.shouldComplete("x = ", 4))
        assertFalse(CompletionMapping.shouldComplete("f(a, ", 5))
        assertFalse(CompletionMapping.shouldComplete("", 0))
        // Numbers: "1." is a float, not an attribute access; "x1" is still a name.
        assertFalse(CompletionMapping.shouldComplete("y = 12", 6))
        assertFalse(CompletionMapping.shouldComplete("y = 1.", 6))
        assertTrue(CompletionMapping.shouldComplete("x1", 2))
        // Comments.
        assertFalse(CompletionMapping.shouldComplete("x = 1  # pri", 12))
    }
}
