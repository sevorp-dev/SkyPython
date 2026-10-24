package dev.skyloft.skypython.console

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleLinesTest {

    @Test
    fun splitsSegmentsIntoLinesKeepingErrorFlag() {
        val lines = ConsoleViewModel.toLines(
            listOf(Segment("a\nb", false), Segment("c\n", true), Segment("d", false)),
        )
        assertEquals(
            listOf(
                ConsoleLine(listOf(Segment("a", false))),
                ConsoleLine(listOf(Segment("b", false), Segment("c", true))),
                ConsoleLine(listOf(Segment("d", false))),
            ),
            lines,
        )
    }

    @Test
    fun promptWithoutNewlineIsItsOwnTrailingLine() {
        val lines = ConsoleViewModel.toLines(listOf(Segment("Name: ", false)))
        assertEquals(listOf(ConsoleLine(listOf(Segment("Name: ", false)))), lines)
    }

    @Test
    fun emptyLinesArePreserved() {
        val lines = ConsoleViewModel.toLines(listOf(Segment("x\n\ny\n", false)))
        assertEquals(3, lines.size)
        assertEquals(ConsoleLine(emptyList()), lines[1])
    }

    @Test
    fun exitLineCopyMatchesSpec() {
        assertEquals("\n[Program finished, exit code 1]\n", ConsoleViewModel.exitLine(1))
    }
}
