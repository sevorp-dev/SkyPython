package dev.skyloft.skypython.editor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review finding #10: don't materialise the whole text on every keystroke. */
@OptIn(ExperimentalCoroutinesApi::class)
class TextChangeDebouncerTest {

    private val scope = TestScope()
    private val emitted = mutableListOf<Pair<String, String>>()
    private var materialised = 0
    private val debouncer = TextChangeDebouncer(scope, delayMs = 300) { id, text -> emitted += id to text }

    private fun type(id: String, text: String) = debouncer.onChanged(id) { materialised++; text }

    @Test
    fun burstOfKeystrokesMaterialisesTextOnceAfterThePause() {
        type("a", "p"); type("a", "pr"); type("a", "pri")
        scope.advanceTimeBy(299); scope.runCurrent()
        assertEquals(0, materialised)
        assertEquals(emptyList<Pair<String, String>>(), emitted)
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(1, materialised)
        assertEquals(listOf("a" to "pri"), emitted)
    }

    @Test
    fun flushDeliversPendingTextImmediatelyAndOnlyOnce() {
        type("a", "x = 1")
        debouncer.flush()
        assertEquals(listOf("a" to "x = 1"), emitted)
        scope.advanceTimeBy(1_000); scope.runCurrent()
        debouncer.flush()
        assertEquals(listOf("a" to "x = 1"), emitted)
    }

    @Test
    fun changeInAnotherDocFlushesThePreviousDocFirst() {
        type("a", "A")
        type("b", "B")
        assertEquals(listOf("a" to "A"), emitted)
        scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(listOf("a" to "A", "b" to "B"), emitted)
    }

    @Test
    fun flushWithNothingPendingDoesNothing() {
        debouncer.flush()
        assertEquals(0, materialised)
        assertEquals(emptyList<Pair<String, String>>(), emitted)
    }
}
