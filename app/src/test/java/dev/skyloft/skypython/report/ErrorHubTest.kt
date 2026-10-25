package dev.skyloft.skypython.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorHubTest {

    private var time = 0L
    private val hub = ErrorHub(now = { time }, logTail = { listOf("W/Pty: failed") }, screen = { "terminal" })

    @Test fun `a reported throwable becomes the current notice with its context`() {
        val r = hub.report("terminal", IOException("no /dev/ptmx"))!!
        assertEquals(r, hub.notice.value)
        assertEquals(ReportKind.Error, r.kind)
        assertEquals("terminal", r.where)
        assertEquals("terminal", r.screen)
        assertEquals("java.io.IOException: no /dev/ptmx", r.message)
        assertTrue(r.stackTrace!!.startsWith("java.io.IOException: no /dev/ptmx"))
        assertEquals(listOf("W/Pty: failed"), r.log)
        assertEquals(r, hub.find(r.id))
    }

    @Test fun `a message works without a throwable`() {
        val r = hub.report("language server", "keeps dying")!!
        assertEquals("keeps dying", r.message)
        assertNull(r.stackTrace)
    }

    @Test fun `the same problem is not repeated within the window`() {
        assertNotNull(hub.report("pip", "ensurepip failed"))
        time += 5_000
        assertNull(hub.report("pip", "ensurepip failed"))
        time += ErrorHub.REPEAT_WINDOW_MS
        assertNotNull(hub.report("pip", "ensurepip failed"))
        assertNotNull(hub.report("debugger", "ensurepip failed"))
    }

    @Test fun `dismiss hides the notice but keeps the report for later`() {
        val r = hub.report("debugger", "no socket")!!
        hub.dismiss()
        assertNull(hub.notice.value)
        assertEquals(r, hub.find(r.id))
        assertEquals(listOf(r), hub.recent())
    }

    @Test fun `keeps only the latest few errors`() {
        repeat(8) { hub.report("w$it", "m") }
        assertEquals((7 downTo 3).map { "w$it" }, hub.recent().map { it.where })
    }
}
