package dev.skyloft.skypython.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DocBuffersTest {

    private class Buf(val text: String)

    @Test
    fun aDocKeepsItsBufferUntilItsTabCloses() {
        val buffers = DocBuffers<Buf>()
        val a = buffers.getOrPut("a") { Buf("one") }
        // Seeded once: later text from the view model does not replace the live buffer (and its undo history).
        assertSame(a, buffers.getOrPut("a") { Buf("two") })
        assertSame(a, buffers["a"])
        buffers.retain(setOf("b"))
        assertNull(buffers["a"])
        assertNotSame(a, buffers.getOrPut("a") { Buf("three") })
    }

    @Test
    fun scrollIsKeptPerDocAndDroppedWithIt() {
        val buffers = DocBuffers<Buf>()
        buffers.getOrPut("a") { Buf("") }
        buffers.saveScroll("a", 10, 400)
        buffers.saveScroll("b", 0, 7) // a doc with no buffer: nothing to keep
        assertEquals(DocBuffers.Scroll(10, 400), buffers.scrollOf("a"))
        assertNull(buffers.scrollOf("b"))
        buffers.retain(emptySet())
        assertNull(buffers.scrollOf("a"))
    }

    @Test
    fun releaseDropsOneDoc() {
        val buffers = DocBuffers<Buf>()
        buffers.getOrPut("a") { Buf("") }
        buffers.getOrPut("b") { Buf("") }
        buffers.saveScroll("a", 1, 2)
        buffers.release("a")
        assertNull(buffers["a"])
        assertNull(buffers.scrollOf("a"))
        assertEquals(setOf("b"), buffers.ids)
    }
}
