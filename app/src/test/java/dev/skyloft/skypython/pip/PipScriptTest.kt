package dev.skyloft.skypython.pip

import org.junit.Assert.assertEquals
import org.junit.Test

class PipScriptTest {

    @Test
    fun plainTextIsDoubleQuoted() {
        assertEquals("\"six\"", PipScript.pythonString("six"))
    }

    @Test
    fun quotesAndBackslashesAreEscaped() {
        assertEquals("\"a\\\"b\\\\c'd\"", PipScript.pythonString("a\"b\\c'd"))
    }

    @Test
    fun controlCharactersAreEscaped() {
        assertEquals(
            "\"\\n\\r\\t\\x00\\x1b\\x7f\"",
            PipScript.pythonString("\n\r\t\u0000\u001b\u007f"),
        )
    }

    @Test
    fun nonAsciiIsKeptAsUtf8() {
        assertEquals("\"пакет😀\"", PipScript.pythonString("пакет😀"))
    }

    @Test
    fun loneSurrogateIsEscaped() {
        assertEquals("\"a\\ud800b\"", PipScript.pythonString("a\uD800b"))
    }

    @Test
    fun sourceRunsModuleWithArgvAsListLiteral() {
        assertEquals(
            """
            import runpy, sys
            sys.argv = ["pip", "install", "a\"b"]
            runpy.run_module("pip", run_name="__main__", alter_sys=True)

            """.trimIndent(),
            PipScript.source("pip", listOf("install", "a\"b")),
        )
    }
}
