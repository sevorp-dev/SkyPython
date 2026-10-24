package dev.skyloft.skypython.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleBufferTest {

    private fun line(vararg pieces: Segment) = ConsoleLine(pieces.toList())
    private fun out(t: String) = Segment(t, false)
    private fun err(t: String) = Segment(t, true)
    private fun ConsoleLine.text() = pieces.joinToString("") { it.text }

    /** Chars held by [lines], counting one newline per line (what the cap measures). */
    private fun List<ConsoleLine>.charCount() = sumOf { it.text().length + 1 }

    @Test
    fun adjacentSameKindAppendsAreMergedWithinALine() {
        val buf = ConsoleBuffer()
        buf.append("Hello, ", isError = false)
        buf.append("world\n", isError = false)
        assertEquals(listOf(line(out("Hello, world"))), buf.lines)
    }

    @Test
    fun differentKindsBecomeSeparatePieces() {
        val buf = ConsoleBuffer()
        buf.append("out", false)
        buf.append("err1", true)
        buf.append("err2", true)
        buf.append("out2", false)
        assertEquals(listOf(line(out("out"), err("err1err2"), out("out2"))), buf.lines)
    }

    @Test
    fun emptyAppendIsIgnored() {
        val buf = ConsoleBuffer()
        val v = buf.version
        buf.append("", true)
        assertTrue(buf.lines.isEmpty())
        assertEquals(v, buf.version)
    }

    @Test
    fun splitsIntoLinesKeepingErrorFlag() {
        val buf = ConsoleBuffer()
        buf.append("a\nb", false)
        buf.append("c\n", true)
        buf.append("d", false)
        assertEquals(listOf(line(out("a")), line(out("b"), err("c")), line(out("d"))), buf.lines)
    }

    @Test
    fun promptWithoutNewlineIsItsOwnTrailingLine() {
        val buf = ConsoleBuffer()
        buf.append("Name: ", false)
        assertEquals(listOf(line(out("Name: "))), buf.lines)
    }

    @Test
    fun emptyLinesArePreserved() {
        val buf = ConsoleBuffer()
        buf.append("x\n\ny\n", false)
        assertEquals(listOf(line(out("x")), line(), line(out("y"))), buf.lines)
    }

    @Test
    fun millionShortLinesStayWithinCapAndAreFast() {
        val buf = ConsoleBuffer()
        val start = System.nanoTime()
        for (i in 0 until 1_000_000) buf.append("line $i\n", isError = false)
        val ms = (System.nanoTime() - start) / 1_000_000
        val lines = buf.lines
        assertTrue("chars ${lines.charCount()}", lines.charCount() <= 200_000)
        assertEquals("line 999999", lines.last().text())
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test
    fun completedLinesAreReusedNotRebuiltBetweenSnapshots() {
        val buf = ConsoleBuffer()
        buf.append("first\nsecond\npartial", false)
        val before = buf.lines
        buf.append(" more", false)
        val after = buf.lines
        assertSame(before[0], after[0])
        assertSame(before[1], after[1])
        assertEquals(line(out("partial more")), after[2])
    }

    @Test
    fun singleHugeLineWithoutNewlineIsHardWrapped() {
        val buf = ConsoleBuffer()
        val chunk = "x".repeat(8 * 1024) // the size PythonProcess reads in
        var written = 0
        while (written < 300_000) {
            val n = minOf(chunk.length, 300_000 - written)
            buf.append(chunk.substring(0, n), isError = false)
            written += n
        }
        buf.append("END", false)
        val lines = buf.lines
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.text().length <= 1_000 })
        assertTrue("chars ${lines.charCount()}", lines.charCount() <= 200_000)
        // 300_000 is a multiple of 1_000, so the tail wraps onto its own line.
        assertEquals("x".repeat(1_000), lines[lines.size - 2].text())
        assertEquals("END", lines.last().text())
    }

    @Test
    fun wrappingKeepsErrorFlagsAcrossTheBoundary() {
        val buf = ConsoleBuffer(cap = 100, maxLineLength = 4)
        buf.append("ab", false)
        buf.append("cdef", true)
        assertEquals(listOf(line(out("ab"), err("cd")), line(err("ef"))), buf.lines)
    }

    @Test
    fun lineOfExactlyMaxLengthThenNewlineAddsNoBlankLine() {
        val buf = ConsoleBuffer(cap = 100, maxLineLength = 4)
        buf.append("abcd\nx", false)
        assertEquals(listOf(line(out("abcd")), line(out("x"))), buf.lines)
    }

    @Test
    fun trimmingDropsWholeLeadingLinesAndKeepsErrorFlags() {
        val buf = ConsoleBuffer(cap = 20, maxLineLength = 5)
        buf.append("aaaa\n", false)
        buf.append("bbbb\n", true)
        buf.append("cccc\ndddd\n", false)
        assertEquals(4, buf.lines.size) // exactly 20 chars: nothing dropped yet
        buf.append("eeee\n", false)
        assertEquals(
            listOf(line(err("bbbb")), line(out("cccc")), line(out("dddd")), line(out("eeee"))),
            buf.lines,
        )
    }

    @Test
    fun crlfIsANewlineAndBareCarriageReturnIsDropped() {
        val buf = ConsoleBuffer()
        buf.append("a\r\nb\r", false)
        buf.append("\n50%\r100%\n", false)
        assertEquals(listOf(line(out("a")), line(out("b")), line(out("50%100%"))), buf.lines)
    }

    @Test
    fun endsWithNewlineTracksLastRealChar() {
        val buf = ConsoleBuffer()
        assertTrue(buf.endsWithNewline)
        buf.append("a", false)
        assertFalse(buf.endsWithNewline)
        buf.append("a\n", false)
        assertTrue(buf.endsWithNewline)
        buf.append("\r", false)
        assertTrue(buf.endsWithNewline)
    }

    @Test
    fun versionIncreasesOnEveryChangeIncludingClear() {
        val buf = ConsoleBuffer()
        val v0 = buf.version
        buf.append("a", false)
        val v1 = buf.version
        buf.append("b", false) // same line count, still a change
        val v2 = buf.version
        buf.clear()
        assertTrue(v0 < v1 && v1 < v2 && v2 < buf.version)
    }

    @Test
    fun clearEmptiesBuffer() {
        val buf = ConsoleBuffer()
        buf.append("x\n", false)
        buf.clear()
        assertTrue(buf.lines.isEmpty())
        assertTrue(buf.endsWithNewline)
        buf.append("y", false)
        assertEquals(listOf(line(out("y"))), buf.lines)
    }
}
