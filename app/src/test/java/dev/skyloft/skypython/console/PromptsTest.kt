package dev.skyloft.skypython.console

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PromptsTest {

    private val interpreter = RunTarget.Interpreter
    private val terminal = RunTarget.Terminal
    private val script = RunTarget.Script(File("/p/a.py"))

    @Test
    fun aChunkThatIsExactlyAPythonPromptIsAllPrompt() {
        assertEquals(4, promptLength(">>> ", interpreter))
        assertEquals(4, promptLength("... ", interpreter))
    }

    @Test
    fun aPromptAfterANewlineEndsTheChunk() {
        val chunk = "Traceback (most recent call last):\nZeroDivisionError: division by zero\n>>> "
        assertEquals(4, promptLength(chunk, interpreter))
    }

    @Test
    fun promptLikeTextInsideALineIsNotAPrompt() {
        assertEquals(0, promptLength("error: x>>> ", interpreter))
        assertEquals(0, promptLength(">>> \n", interpreter))
        assertEquals(0, promptLength(">>>", interpreter))
        assertEquals(0, promptLength("", interpreter))
    }

    @Test
    fun scriptsHaveNoPrompts() {
        assertEquals(0, promptLength(">>> ", script))
    }

    @Test
    fun shellPromptIsTheWholeLastLineEndingInDollarOrHash() {
        assertEquals("generic:/data/x $ ".length, promptLength("generic:/data/x $ ", terminal))
        assertEquals(2, promptLength("sh: foo: not found\n$ ", terminal))
        assertEquals(2, promptLength("# ", terminal))
        assertEquals(0, promptLength("sh: foo: not found\n", terminal))
    }

    @Test
    fun theTerminalAlsoKnowsPythonPrompts() {
        assertEquals(4, promptLength(">>> ", terminal))
    }

    @Test
    fun theInterpreterDoesNotTreatShellPromptsAsPrompts() {
        assertEquals(0, promptLength("$ ", interpreter))
    }
}
