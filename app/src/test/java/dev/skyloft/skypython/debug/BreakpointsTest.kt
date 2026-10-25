package dev.skyloft.skypython.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakpointShiftTest {

    private fun bps(vararg lines: Int) = lines.map { Breakpoint(it) }
    private fun lines(list: List<Breakpoint>) = list.map { it.line }

    // Editor positions are 0-based; breakpoint lines are 1-based (line 5 = editor line 4).

    @Test fun `typing on a line moves nothing`() {
        val edit = LineEdit.Insert(startLine = 4, startColumn = 3, endLine = 4)
        assertEquals(listOf(3, 5, 9), lines(BreakpointShift.apply(bps(3, 5, 9), edit)))
    }

    @Test fun `enter in the middle of a line moves the lines below`() {
        val edit = LineEdit.Insert(startLine = 4, startColumn = 3, endLine = 5)
        assertEquals(listOf(3, 5, 10), lines(BreakpointShift.apply(bps(3, 5, 9), edit)))
    }

    @Test fun `enter at the start of a line moves that line down too`() {
        val edit = LineEdit.Insert(startLine = 4, startColumn = 0, endLine = 6)
        assertEquals(listOf(3, 7, 11), lines(BreakpointShift.apply(bps(3, 5, 9), edit)))
    }

    @Test fun `backspace joining a line into the previous one takes its breakpoint along`() {
        // Line 6 (editor 5) joined onto the end of line 5 (editor 4).
        val edit = LineEdit.Delete(startLine = 4, startColumn = 12, endLine = 5)
        assertEquals(listOf(3, 5, 8), lines(BreakpointShift.apply(bps(3, 6, 9), edit)))
    }

    @Test fun `joining keeps the breakpoint already on the line and its condition`() {
        val edit = LineEdit.Delete(startLine = 4, startColumn = 12, endLine = 5)
        val result = BreakpointShift.apply(listOf(Breakpoint(5, "x > 1"), Breakpoint(6)), edit)
        assertEquals(listOf(Breakpoint(5, "x > 1")), result)
    }

    @Test fun `deleting whole lines drops their breakpoints and shifts the rest up`() {
        // Lines 4..6 selected from their start to the start of line 7, then deleted.
        val edit = LineEdit.Delete(startLine = 3, startColumn = 0, endLine = 6)
        assertEquals(listOf(2, 4, 7), lines(BreakpointShift.apply(bps(2, 4, 5, 6, 7, 10), edit)))
    }

    @Test fun `deleting within one line moves nothing`() {
        val edit = LineEdit.Delete(startLine = 4, startColumn = 0, endLine = 4)
        assertEquals(listOf(5), lines(BreakpointShift.apply(bps(5), edit)))
    }

    @Test fun `result stays sorted and without duplicates`() {
        val edit = LineEdit.Delete(startLine = 0, startColumn = 0, endLine = 2)
        assertEquals(listOf(1, 2), lines(BreakpointShift.apply(bps(1, 2, 3, 4), edit)))
    }
}

class BreakpointStoreTest {

    private val main = "/data/files/projects/main.py"
    private val other = "/data/files/projects/lib/util.py"

    @Test fun `toggle adds and removes and reports the new state`() {
        val store = BreakpointStore(null) {}
        assertTrue(store.toggle(main, 4))
        assertTrue(store.toggle(main, 2))
        assertEquals(listOf(Breakpoint(2), Breakpoint(4)), store.state.value[main])
        assertFalse(store.toggle(main, 4))
        assertEquals(listOf(Breakpoint(2)), store.state.value[main])
        assertFalse(store.toggle(main, 2))
        assertFalse(main in store.state.value)
    }

    @Test fun `breakpoints and watches survive a restart`() {
        var saved: String? = null
        val store = BreakpointStore(null) { saved = it }
        store.toggle(main, 3)
        store.setCondition(main, 3, "i == 2")
        store.toggle(other, 10)
        store.addWatch("len(items)")
        store.addWatch("total")
        store.removeWatch(0)

        val again = BreakpointStore(saved) {}
        assertEquals(listOf(Breakpoint(3, "i == 2")), again.state.value[main])
        assertEquals(listOf(Breakpoint(10)), again.state.value[other])
        assertEquals(listOf("total"), again.watches.value)
    }

    @Test fun `blank condition means none`() {
        val store = BreakpointStore(null) {}
        store.toggle(main, 3)
        store.setCondition(main, 3, "  ")
        assertEquals(listOf(Breakpoint(3, null)), store.state.value[main])
    }

    @Test fun `edits shift only the edited file`() {
        val store = BreakpointStore(null) {}
        store.toggle(main, 5)
        store.toggle(other, 5)
        store.applyEdit(main, LineEdit.Insert(startLine = 0, startColumn = 0, endLine = 2))
        assertEquals(listOf(Breakpoint(7)), store.state.value[main])
        assertEquals(listOf(Breakpoint(5)), store.state.value[other])
    }

    @Test fun `renaming a file moves its breakpoints`() {
        val store = BreakpointStore(null) {}
        store.toggle(main, 5)
        store.rename(main, "/data/files/projects/app.py")
        assertFalse(main in store.state.value)
        assertEquals(listOf(Breakpoint(5)), store.state.value["/data/files/projects/app.py"])
    }

    @Test fun `renaming a folder moves the breakpoints of the files inside`() {
        val store = BreakpointStore(null) {}
        store.toggle(other, 2)
        store.toggle("/data/files/projects/libra.py", 1)
        store.rename("/data/files/projects/lib", "/data/files/projects/tools")
        assertEquals(listOf(Breakpoint(2)), store.state.value["/data/files/projects/tools/util.py"])
        assertEquals(listOf(Breakpoint(1)), store.state.value["/data/files/projects/libra.py"])
        assertFalse(other in store.state.value)
    }

    @Test fun `deleting a file drops its breakpoints`() {
        var saved: String? = null
        val store = BreakpointStore(null) { saved = it }
        store.toggle(main, 5)
        store.toggle(other, 2)
        store.remove(main)
        assertFalse(main in store.state.value)
        assertEquals(listOf(Breakpoint(2)), store.state.value[other])
        assertFalse(main in BreakpointStore(saved) {}.state.value)
    }

    @Test fun `deleting a folder drops the breakpoints of the files inside only`() {
        val store = BreakpointStore(null) {}
        store.toggle(other, 2)
        store.toggle("/data/files/projects/libra.py", 1)
        store.remove("/data/files/projects/lib/")
        assertFalse(other in store.state.value)
        assertEquals(listOf(Breakpoint(1)), store.state.value["/data/files/projects/libra.py"])
    }

    @Test fun `saved breakpoints loaded later merge with ones set meanwhile`() {
        val old = run {
            var json = ""
            val store = BreakpointStore(null) { json = it }
            store.toggle(main, 3)
            store.toggle(other, 7)
            store.addWatch("total")
            json
        }
        var saved: String? = null
        val store = BreakpointStore(null) { saved = it }
        store.toggle(main, 9) // set before the saved file was read: kept
        store.addWatch("n")
        store.restore(old)
        assertEquals(listOf(Breakpoint(9)), store.state.value[main])
        assertEquals(listOf(Breakpoint(7)), store.state.value[other])
        assertEquals(listOf("total", "n"), store.watches.value)
        val again = BreakpointStore(saved) {}
        assertEquals(store.state.value, again.state.value)
    }

    @Test fun `corrupt saved data starts empty`() {
        val store = BreakpointStore("{not json") {}
        assertTrue(store.state.value.isEmpty())
        assertTrue(store.watches.value.isEmpty())
    }
}
