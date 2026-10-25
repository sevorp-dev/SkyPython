package dev.skyloft.skypython.console

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLineRegexTest {
    private val regex = statusLineRegex(
        footers = listOf("[Программа завершена, код выхода %1\$d]", "[Program finished, exit code %1\$d]"),
        cancelled = listOf("[Отменено]", "[Cancelled]"),
    )

    @Test fun `matches footers in both languages, negative codes too`() {
        assertTrue(regex.matches("[Program finished, exit code 0]"))
        assertTrue(regex.matches("[Программа завершена, код выхода -15]"))
        assertTrue(regex.matches("[Отменено]"))
        assertTrue(regex.matches("[Cancelled]"))
    }

    @Test fun `does not match program output`() {
        assertFalse(regex.matches("[Program finished, exit code x]"))
        assertFalse(regex.matches("Hello [Cancelled]"))
    }
}
