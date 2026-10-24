package dev.skyloft.skypython.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleBufferTest {

    @Test
    fun adjacentSameKindAppendsAreMerged() {
        val buf = ConsoleBuffer()
        buf.append("Hello, ", isError = false)
        buf.append("world\n", isError = false)
        assertEquals(listOf(Segment("Hello, world\n", false)), buf.segments)
    }

    @Test
    fun differentKindsStartNewSegments() {
        val buf = ConsoleBuffer()
        buf.append("out", false)
        buf.append("err1", true)
        buf.append("err2", true)
        buf.append("out2", false)
        assertEquals(
            listOf(Segment("out", false), Segment("err1err2", true), Segment("out2", false)),
            buf.segments,
        )
    }

    @Test
    fun emptyAppendIsIgnored() {
        val buf = ConsoleBuffer()
        buf.append("", true)
        assertTrue(buf.segments.isEmpty())
    }

    @Test
    fun capKeepsNewestCharsOfDefault200k() {
        val buf = ConsoleBuffer()
        val chunk = "0123456789".repeat(1_000) // 10_000 chars
        repeat(25) { buf.append(chunk, false) } // 250_000 chars
        buf.append("END", false)
        val text = buf.segments.joinToString("") { it.text }
        assertEquals(200_000, text.length)
        assertTrue(text.endsWith("789END"))
        assertEquals(1, buf.segments.size)
    }

    @Test
    fun trimmingDropsWholeOldSegmentsAndCutsPartialOne() {
        val buf = ConsoleBuffer(cap = 10)
        buf.append("aaaa", false)
        buf.append("bbbb", true)
        buf.append("cccc", false)
        assertEquals(
            listOf(Segment("aa", false), Segment("bbbb", true), Segment("cccc", false)),
            buf.segments,
        )
        buf.append("dddddddd", true)
        assertEquals(listOf(Segment("cc", false), Segment("dddddddd", true)), buf.segments)
    }

    @Test
    fun errorSegmentsArePreservedThroughTrimming() {
        val buf = ConsoleBuffer(cap = 1_000)
        repeat(500) { buf.append("x".repeat(10), false) }
        buf.append("Traceback: boom\n", true)
        repeat(20) { buf.append("y", false) }
        val segs = buf.segments
        assertEquals(1_000, segs.sumOf { it.text.length })
        assertEquals(Segment("Traceback: boom\n", true), segs[1])
        assertEquals(Segment("y".repeat(20), false), segs[2])
    }

    @Test
    fun singleAppendLongerThanCapKeepsItsTail() {
        val buf = ConsoleBuffer(cap = 5)
        buf.append("abcdefgh", true)
        assertEquals(listOf(Segment("defgh", true)), buf.segments)
    }

    @Test
    fun clearEmptiesBuffer() {
        val buf = ConsoleBuffer()
        buf.append("x", false)
        buf.clear()
        assertTrue(buf.segments.isEmpty())
        buf.append("y", false)
        assertEquals(listOf(Segment("y", false)), buf.segments)
    }
}
