package dev.skyloft.skypython.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineEditsTest {
    /** Applies [edit] to [text] the way the editor does (line/column range replaced). */
    private fun apply(text: String, edit: LineEdit?): String {
        if (edit == null) return text
        val lines = text.split('\n')
        fun index(line: Int, col: Int) = lines.take(line).sumOf { it.length + 1 } + col
        val start = index(edit.startLine, edit.startColumn)
        val end = index(edit.endLine, edit.endColumn)
        return text.substring(0, start) + edit.text + text.substring(end)
    }

    private fun lines(text: String) = text.split('\n')
    private fun sel(sl: Int, sc: Int, el: Int = sl, ec: Int = sc) = TextSelection(sl, sc, el, ec)

    // ---- comment ----

    @Test
    fun commentsAnUncommentedLineAtItsIndent() {
        val t = "if x:\n    y = 1\n"
        val e = LineEdits.toggleComment(lines(t), sel(1, 6))
        assertEquals("if x:\n    # y = 1\n", apply(t, e))
        assertEquals(sel(1, 8), e!!.selection)
    }

    @Test
    fun uncommentsACommentedLine() {
        val t = "    # y = 1"
        val e = LineEdits.toggleComment(lines(t), sel(0, 10))
        assertEquals("    y = 1", apply(t, e))
        assertEquals(sel(0, 8), e!!.selection)
    }

    @Test
    fun uncommentsWithoutSpaceAfterHash() {
        assertEquals("y = 1", apply("#y = 1", LineEdits.toggleComment(lines("#y = 1"), sel(0, 0))))
    }

    @Test
    fun commentsABlockAtItsSmallestIndentSkippingBlankLines() {
        val t = "def f():\n    a = 1\n\n    if a:\n        b = 2"
        val e = LineEdits.toggleComment(lines(t), sel(1, 0, 4, 13))
        assertEquals("def f():\n    # a = 1\n\n    # if a:\n    #     b = 2", apply(t, e))
    }

    @Test
    fun mixedBlockGetsCommentedThenUncommentedBack() {
        val t = "a = 1\n# b = 2"
        val commented = apply(t, LineEdits.toggleComment(lines(t), sel(0, 0, 1, 7)))
        assertEquals("# a = 1\n# # b = 2", commented)
        val back = apply(commented, LineEdits.toggleComment(lines(commented), sel(0, 0, 1, 9)))
        assertEquals(t, back)
    }

    @Test
    fun selectionEndingAtColumnZeroExcludesThatLine() {
        val t = "a\nb\nc"
        assertEquals("# a\n# b\nc", apply(t, LineEdits.toggleComment(lines(t), sel(0, 0, 2, 0))))
    }

    @Test
    fun blankOnlyLinesAreLeftAlone() {
        assertNull(LineEdits.toggleComment(lines("\n   \n"), sel(0, 0, 1, 3)))
    }

    // ---- indent / outdent ----

    @Test
    fun indentAddsFourSpacesToEachNonEmptyLine() {
        val t = "a\n\nb"
        val e = LineEdits.indent(lines(t), sel(0, 1, 2, 1))
        assertEquals("    a\n\n    b", apply(t, e))
        assertEquals(sel(0, 5, 2, 5), e!!.selection)
    }

    @Test
    fun outdentRemovesUpToFourSpaces() {
        val t = "      a\n  b\nc"
        val e = LineEdits.outdent(lines(t), sel(0, 7, 2, 1))
        assertEquals("  a\nb\nc", apply(t, e))
        assertEquals(sel(0, 3, 2, 1), e!!.selection)
    }

    @Test
    fun outdentRemovesALeadingTab() {
        assertEquals("a", apply("\ta", LineEdits.outdent(lines("\ta"), sel(0, 0))))
    }

    @Test
    fun outdentWithNothingToRemoveIsNoOp() {
        assertNull(LineEdits.outdent(lines("a\nb"), sel(0, 0, 1, 1)))
    }

    @Test
    fun outdentCursorInsideIndentMovesToLineStart() {
        val e = LineEdits.outdent(lines("    a"), sel(0, 2))
        assertEquals(sel(0, 0), e!!.selection)
    }

    // ---- duplicate / delete ----

    @Test
    fun duplicateCopiesTheLineBelowAndMovesTheCursorDown() {
        val t = "a = 1\nb = 2"
        val e = LineEdits.duplicate(lines(t), sel(0, 3))
        assertEquals("a = 1\na = 1\nb = 2", apply(t, e))
        assertEquals(sel(1, 3), e!!.selection)
    }

    @Test
    fun duplicateCopiesASelectedBlock() {
        val t = "a\nb\nc"
        val e = LineEdits.duplicate(lines(t), sel(0, 0, 1, 1))
        assertEquals("a\nb\na\nb\nc", apply(t, e))
        assertEquals(sel(2, 0, 3, 1), e!!.selection)
    }

    @Test
    fun duplicateLastLine() {
        assertEquals("a\nb\nb", apply("a\nb", LineEdits.duplicate(lines("a\nb"), sel(1, 0))))
    }

    @Test
    fun deleteRemovesTheLineAndItsNewline() {
        val t = "a\nbb\nc"
        val e = LineEdits.deleteLines(lines(t), sel(1, 1))
        assertEquals("a\nc", apply(t, e))
        assertEquals(sel(1, 1), e!!.selection)
    }

    @Test
    fun deleteLastLineRemovesThePrecedingNewline() {
        val t = "a\nbb"
        val e = LineEdits.deleteLines(lines(t), sel(1, 2))
        assertEquals("a", apply(t, e))
        assertEquals(sel(0, 1), e!!.selection)
    }

    @Test
    fun deleteOnlyLineEmptiesTheDocument() {
        assertEquals("", apply("abc", LineEdits.deleteLines(lines("abc"), sel(0, 1))))
    }

    @Test
    fun deleteASelectedBlock() {
        assertEquals("d", apply("a\nb\nc\nd", LineEdits.deleteLines(lines("a\nb\nc\nd"), sel(0, 0, 2, 1))))
    }

    // ---- move ----

    @Test
    fun moveUpSwapsWithThePreviousLine() {
        val t = "a\nb\nc"
        val e = LineEdits.moveUp(lines(t), sel(2, 1))
        assertEquals("a\nc\nb", apply(t, e))
        assertEquals(sel(1, 1), e!!.selection)
    }

    @Test
    fun moveDownMovesABlock() {
        val t = "a\nb\nc\nd"
        val e = LineEdits.moveDown(lines(t), sel(0, 0, 1, 1))
        assertEquals("c\na\nb\nd", apply(t, e))
        assertEquals(sel(1, 0, 2, 1), e!!.selection)
    }

    @Test
    fun moveAtTheEdgesIsNoOp() {
        assertNull(LineEdits.moveUp(lines("a\nb"), sel(0, 0)))
        assertNull(LineEdits.moveDown(lines("a\nb"), sel(1, 0)))
    }

    // ---- go to line ----

    @Test
    fun goToLineParsesAndClamps() {
        assertEquals(0, parseGoToLine("1", 10))
        assertEquals(4, parseGoToLine(" 5 ", 10))
        assertEquals(9, parseGoToLine("999", 10))
        assertEquals(0, parseGoToLine("0", 10))
        assertNull(parseGoToLine("", 10))
        assertNull(parseGoToLine("abc", 10))
        assertNull(parseGoToLine("-3", 10))
    }
}
