package dev.skyloft.skypython.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunFooterTest {

    @Test
    fun exitCodesAreShellStyle() {
        assertEquals(0, RunFooter.exitCode(0))
        assertEquals(3, RunFooter.exitCode(3))
        // Killed by SIGINT (an uncaught KeyboardInterrupt) / SIGKILL (Stop): 128 + signal.
        assertEquals(130, RunFooter.exitCode(-2))
        assertEquals(137, RunFooter.exitCode(-9))
    }

    @Test
    fun footerIsADimmedLineOfItsOwn() {
        val atStart = RunFooter.text(0, atLineStart = true)
        // Dimmed, then the cursor is hidden: nothing reads input any more.
        assertEquals("\u001b[2m[Program finished, exit code 0]\u001b[0m\r\n\u001b[?25l", atStart)
        val midLine = RunFooter.text(1, atLineStart = false)
        assertEquals("\r\n\u001b[2m[Program finished, exit code 1]\u001b[0m\r\n\u001b[?25l", midLine)
    }

    @Test
    fun footerOnTheScreenReadsAsPlainText() {
        val emulator = emulator(40, 5)
        emulator.feed("no newline at the end")
        emulator.feed(RunFooter.text(-2, atLineStart = emulator.cursorCol == 0))
        assertEquals(
            "no newline at the end\n[Program finished, exit code 130]",
            Transcript.of(emulator.screen),
        )
    }
}

class TranscriptTest {

    @Test
    fun wrappedLinesAreJoinedAndTrailingBlanksDropped() {
        val emulator = emulator(10, 4)
        emulator.feed("0123456789abcdef\r\nsecond   \r\n")
        assertEquals("0123456789abcdef\nsecond", Transcript.of(emulator.screen))
    }

    @Test
    fun includesTheScrollback() {
        val emulator = emulator(20, 3)
        for (i in 1..10) emulator.feed("line $i\r\n")
        val text = Transcript.of(emulator.screen)
        assertTrue(text, text.startsWith("line 1\n"))
        assertTrue(text, text.endsWith("line 10"))
    }

    @Test
    fun coloursAreNotCopied() {
        val emulator = emulator(30, 3)
        emulator.feed("\u001b[31mred\u001b[0m plain")
        assertEquals("red plain", Transcript.of(emulator.screen))
    }

    @Test
    fun logicalLineSpansWrappedRows() {
        val emulator = emulator(10, 8)
        emulator.feed("short\r\n  File \"/p/a.py\", line 7, in <module>\r\nlast")
        // Rows 1..4 hold the wrapped traceback line; tapping any of them gives the whole line.
        for (row in 1..4) {
            assertEquals("  File \"/p/a.py\", line 7, in <module>", Transcript.logicalLine(emulator.screen, row))
        }
        assertEquals("short", Transcript.logicalLine(emulator.screen, 0))
    }
}

class TracebacksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesPythonsFrameLine() {
        assertEquals(
            Tracebacks.Location("/data/projects/hello.py", 12),
            Tracebacks.parse("  File \"/data/projects/hello.py\", line 12, in <module>"),
        )
        assertEquals(
            Tracebacks.Location("a b.py", 3),
            Tracebacks.parse("File \"a b.py\", line 3"),
        )
    }

    @Test
    fun otherLinesAreNotLocations() {
        assertNull(Tracebacks.parse("Traceback (most recent call last):"))
        assertNull(Tracebacks.parse("ValueError: line 3"))
        assertNull(Tracebacks.parse("  File \"<stdin>\", line 1, in <module>"))
    }

    @Test
    fun resolvesOnlyFilesInTheProjects() {
        val projects = tmp.newFolder("projects")
        val script = File(projects, "demo/a.py").apply { parentFile!!.mkdirs(); writeText("x") }
        val outside = tmp.newFile("lib.py")

        assertEquals(script.canonicalFile, Tracebacks.resolve(Tracebacks.Location(script.path, 1), projects, projects))
        // Relative names are relative to the script's folder (the working directory).
        assertEquals(script.canonicalFile, Tracebacks.resolve(Tracebacks.Location("a.py", 1), script.parentFile!!, projects))
        // The standard library and anything else outside Projects is not opened in the editor.
        assertNull(Tracebacks.resolve(Tracebacks.Location(outside.path, 1), projects, projects))
        assertNull(Tracebacks.resolve(Tracebacks.Location(File(projects, "missing.py").path, 1), projects, projects))
    }
}

class OutputFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun outputIsNamedAfterTheScript() {
        assertEquals("hello_output.txt", OutputFiles.name("hello.py"))
        assertEquals("my.tool_output.txt", OutputFiles.name("my.tool.py"))
        assertEquals("Interpreter_output.txt", OutputFiles.name("Interpreter"))
    }

    @Test
    fun anExistingOutputIsNotOverwritten() {
        val dir = tmp.newFolder()
        assertEquals(File(dir, "a_output.txt"), OutputFiles.unique(dir, "a_output.txt"))
        File(dir, "a_output.txt").writeText("old")
        assertEquals(File(dir, "a_output-2.txt"), OutputFiles.unique(dir, "a_output.txt"))
        File(dir, "a_output-2.txt").writeText("old")
        assertEquals(File(dir, "a_output-3.txt"), OutputFiles.unique(dir, "a_output.txt"))
    }

    @Test
    fun outputOfAProjectScriptIsSavedNextToIt() {
        val projects = tmp.newFolder("projects")
        val script = File(projects, "game/main.py").apply { parentFile!!.mkdirs(); writeText("") }
        assertEquals(script.parentFile!!.canonicalFile, OutputFiles.saveDir(script, projects).canonicalFile)
    }

    @Test
    fun outputOfAScriptRunFromACopyGoesToTheProjects() {
        // A file opened with the system picker runs as a copy in cacheDir/run: the cache is not a
        // place to keep output (the user can't see it, Android may clear it).
        val projects = tmp.newFolder("projects")
        val copy = File(tmp.newFolder("cache", "run"), "picked.py").apply { writeText("") }
        assertEquals(projects, OutputFiles.saveDir(copy, projects))
        // A look-alike sibling folder is outside too.
        val sibling = File(tmp.newFolder("projects-old"), "x.py").apply { writeText("") }
        assertEquals(projects, OutputFiles.saveDir(sibling, projects))
        // The interpreter and the shell have no script.
        assertEquals(projects, OutputFiles.saveDir(null, projects))
    }

    @Test
    fun saveNewWritesTheWholeTextUnderAFreeNameAndLeavesNoTemporaryFile() {
        val dir = File(tmp.root, "out") // created on demand
        val first = OutputFiles.saveNew(dir, "a_output.txt", "one\n")
        val second = OutputFiles.saveNew(dir, "a_output.txt", "two\n")
        assertEquals(File(dir, "a_output.txt"), first)
        assertEquals(File(dir, "a_output-2.txt"), second)
        assertEquals("one\n", first.readText())
        assertEquals("two\n", second.readText())
        assertEquals(setOf("a_output.txt", "a_output-2.txt"), dir.list()!!.toSet())
    }

    @Test
    fun everyShareGetsItsOwnFileAndOnlyTheLastFewAreKept() {
        val share = tmp.newFolder("share")
        val files = (1..5).map { OutputFiles.shareFile(share, "a_output.txt").apply { writeText("run $it") } }
        assertTrue(files.all { it.name == "a_output.txt" })
        assertEquals(files.size, files.toSet().size)
        assertEquals("an earlier share may still be read", "run 5", files.last().readText())
        assertTrue(files.takeLast(3).all { it.isFile })
        assertTrue(files.take(2).none { it.exists() })
    }

    @Test
    fun longOutputIsSharedAsAFile() {
        assertFalse(OutputFiles.shareAsFile("short"))
        assertTrue(OutputFiles.shareAsFile("x".repeat(OutputFiles.MAX_SHARE_TEXT + 1)))
    }
}

class OutputActionsTest {

    @Test
    fun whileRunning() {
        val a = OutputActions.of(TerminalKind.Run, running = true, hasOutput = true)
        assertTrue(a.canInterrupt)
        assertTrue(a.canStop)
        assertTrue(a.canPaste)
        assertTrue(a.canType)
        assertFalse(a.canRunAgain)
        assertTrue(a.canCopy)
    }

    @Test
    fun afterTheProgramEnded() {
        val a = OutputActions.of(TerminalKind.Run, running = false, hasOutput = true)
        assertFalse(a.canInterrupt)
        assertFalse(a.canStop)
        // Nothing reads input any more.
        assertFalse(a.canPaste)
        assertFalse(a.canType)
        assertTrue(a.canRunAgain)
        assertTrue(a.canCopy)
        assertTrue(a.canSave)
        assertTrue(a.canShare)
    }

    @Test
    fun nothingToCopyBeforeAnyOutput() {
        val a = OutputActions.of(TerminalKind.Run, running = true, hasOutput = false)
        assertFalse(a.canCopy)
        assertFalse(a.canSave)
        assertFalse(a.canShare)
        assertFalse(a.canClear)
    }

    @Test
    fun interpreterAndShellRestartInsteadOfRunAgain() {
        val a = OutputActions.of(TerminalKind.Interpreter, running = false, hasOutput = true)
        assertFalse(a.canRunAgain)
        assertTrue(a.canRestart)
        assertFalse(OutputActions.of(TerminalKind.Run, running = false, hasOutput = true).canRestart)
    }
}

private fun emulator(columns: Int, rows: Int): TerminalEmulator =
    TerminalEmulator(NullOutput, columns, rows, 10, 20, 100, null)

private fun TerminalEmulator.feed(text: String) {
    val bytes = text.toByteArray()
    append(bytes, bytes.size)
}

private object NullOutput : TerminalOutput() {
    override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onCopyTextToClipboard(text: String?) = Unit
    override fun onPasteTextFromClipboard() = Unit
    override fun onBell() = Unit
    override fun onColorsChanged() = Unit
}
