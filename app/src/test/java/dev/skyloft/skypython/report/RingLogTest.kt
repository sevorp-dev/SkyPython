package dev.skyloft.skypython.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class RingLogTest {

    @Test fun `keeps lines in order until full`() {
        val log = RingLog(3)
        log.add("a")
        log.add("b")
        assertEquals(listOf("a", "b"), log.snapshot())
    }

    @Test fun `drops the oldest lines once full`() {
        val log = RingLog(3)
        (1..5).forEach { log.add("line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), log.snapshot())
    }

    @Test fun `tail returns at most the last n lines`() {
        val log = RingLog(10)
        (1..6).forEach { log.add("$it") }
        assertEquals(listOf("5", "6"), log.tail(2))
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), log.tail(100))
    }

    @Test fun `concurrent writers never exceed capacity or lose order inside one thread`() {
        val log = RingLog(100)
        val threads = (0 until 8).map { t -> thread { repeat(500) { log.add("t$t-$it") } } }
        threads.forEach { it.join() }
        val lines = log.snapshot()
        assertEquals(100, lines.size)
        // Per writer, the surviving lines keep their order.
        lines.groupBy { it.substringBefore('-') }.values.forEach { own ->
            val numbers = own.map { it.substringAfter('-').toInt() }
            assertEquals(numbers.sorted(), numbers)
        }
    }

    @Test fun `AppLog formats level and tag and works without Android`() {
        AppLog.clear()
        AppLog.w("Pty", "could not open", IllegalStateException("no /dev/ptmx"))
        val line = AppLog.tail(1).single()
        assertTrue(line, " W/Pty: could not open" in line)
        assertTrue(line, "IllegalStateException: no /dev/ptmx" in line)
    }
}
