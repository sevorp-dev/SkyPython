package dev.skyloft.skypython.console

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleLinesTest {

    @Test
    fun exitLineCopyMatchesSpec() {
        assertEquals("\n[Program finished, exit code 1]\n", ConsoleViewModel.exitLine(1))
    }
}
