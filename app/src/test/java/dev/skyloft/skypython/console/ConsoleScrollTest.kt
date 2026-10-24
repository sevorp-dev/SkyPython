package dev.skyloft.skypython.console

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleScrollTest {

    @Test
    fun emptyListCountsAsBottom() {
        assertTrue(isNearBottom(lastVisibleIndex = -1, totalItems = 0))
    }

    @Test
    fun lastItemVisibleIsBottom() {
        assertTrue(isNearBottom(lastVisibleIndex = 99, totalItems = 100))
    }

    @Test
    fun oneItemShortIsStillNearBottom() {
        assertTrue(isNearBottom(lastVisibleIndex = 98, totalItems = 100))
    }

    @Test
    fun scrolledUpIsNotBottom() {
        assertFalse(isNearBottom(lastVisibleIndex = 50, totalItems = 100))
    }
}
