package dev.skyloft.skypython.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SnackbarClearanceTest {

    @Test
    fun theHighestBottomControlCounts() {
        val c = SnackbarClearance()
        assertEquals(0, c.px)
        val symbolBar = Any()
        val keyRow = Any()
        c.set(symbolBar, 300)
        c.set(keyRow, 120)
        assertEquals(300, c.px)
        c.remove(symbolBar) // its screen left
        assertEquals(120, c.px)
    }

    @Test
    fun theHostIsLiftedOnlyByWhatItDoesNotAlreadyClear() {
        // The app's bottom bar (under the host) already takes 150 px off the window bottom.
        assertEquals(150, SnackbarClearance.lift(clearancePx = 300, hostGapPx = 150))
        assertEquals(0, SnackbarClearance.lift(clearancePx = 100, hostGapPx = 150))
        assertEquals(0, SnackbarClearance.lift(clearancePx = 0, hostGapPx = 0))
    }
}
