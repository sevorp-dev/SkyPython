package dev.skyloft.skypython.terminal

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import dev.skyloft.skypython.R
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.runtime.PythonInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ▶ Run on a terminal, end to end without the view: [TerminalViewModel] (kind Run) → emulator →
 * PTY → `python -u script`. The test plays the view's part by giving the session its size.
 */
@RunWith(AndroidJUnit4::class)
class RunTerminalDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val projects = File(app.filesDir, "projects")
    private val dir = File(projects, "run-test")
    private val vms = mutableListOf<TerminalViewModel>()

    @Before
    fun setUp() {
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        onMain { vms.forEach { it.kill() } }
        dir.deleteRecursively()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun footer(code: Int) = AppLocale.string(app, R.string.console_exit_footer, code)

    /** Writes [code] to [name] and runs it at 80×24, as the screen would. */
    private fun run(name: String, code: String): TerminalViewModel {
        val script = File(dir, name).apply { writeText(code.trimIndent() + "\n") }
        val installer = PythonInstaller(app)
        val vm = onMain {
            TerminalViewModel(TerminalKind.Run, projects, app, installer::ensureInstalled).also { it.run(script) }
        }
        vms += vm
        val session = waitFor("a session") { vm.state.value.session }
        onMain { session.updateSize(80, 24, 10, 20) }
        return vm
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = TIMEOUT_MS, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            onMain(probe)?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what; screen:\n" + runCatching { onMain { vms.last().transcript() } }.getOrNull())
    }

    private val TerminalViewModel.session: TerminalSession get() = state.value.session!!
    private val TerminalViewModel.screen: TerminalBuffer get() = session.emulator.screen

    private fun TerminalViewModel.await(text: String): String =
        waitFor("\"$text\"") { transcript().takeIf { text in it } }

    private fun TerminalViewModel.type(text: String) = onMain {
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    /** The screen row (scrollback rows negative) whose line contains [text]. */
    private fun TerminalViewModel.rowOf(text: String): Int = onMain {
        val s = screen
        (-s.activeTranscriptRows until s.activeRows - s.activeTranscriptRows)
            .first { text in Transcript.logicalLine(s, it) }
    }

    @Test
    fun inputIsTypedOnTheTerminalAndTheFooterFollows() {
        val vm = run(
            "ask.py",
            """
            name = input("Name? ")
            print("Hi", name)
            """,
        )
        vm.await("Name?")
        vm.type("Ann\r")
        vm.await("Hi Ann")
        vm.await(footer(0))
        assertEquals(0, onMain { vm.state.value.exitCode })
        // The terminal echoed what was typed, after the prompt.
        assertTrue(onMain { vm.transcript() }.contains("Name? Ann"))
        assertEquals("ask.py", onMain { vm.title })
    }

    @Test
    fun ctrlCInterruptsAnEndlessLoop() {
        val vm = run(
            "loop.py",
            """
            print("looping")
            while True:
                pass
            """,
        )
        vm.await("looping")
        Thread.sleep(300)
        onMain { vm.interrupt() }
        vm.await("KeyboardInterrupt")
        // An uncaught KeyboardInterrupt ends Python by SIGINT: 128 + 2, as a shell shows it.
        vm.await(footer(130))
        assertEquals(false, onMain { vm.state.value.running })
    }

    @Test
    fun ansiColoursBecomeStyledCells() {
        val vm = run("colour.py", """print("\033[31mRED\033[0m plain")""")
        vm.await(footer(0))
        val row = vm.rowOf("RED plain")
        onMain {
            val red = vm.screen.getStyleAt(row, 0)
            val plain = vm.screen.getStyleAt(row, 4)
            assertEquals(1, TextStyle.decodeForeColor(red)) // palette red
            assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(plain))
        }
    }

    @Test
    fun tracebacksAreColouredAndTheirFramesOpenTheEditor() {
        val vm = run(
            "boom.py",
            """
            def f():
                raise ValueError("boom")
            f()
            """,
        )
        vm.await("ValueError: boom")
        vm.await(footer(1))
        // Python colours its traceback on a terminal: the error line is not the default colour.
        val errorRow = vm.rowOf("ValueError: boom")
        onMain {
            assertNotEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(vm.screen.getStyleAt(errorRow, 0)))
        }
        // `File ".../boom.py", line 2, in f` opens boom.py at line 2.
        val frameRow = vm.rowOf("line 2, in f")
        assertTrue(onMain { vm.openTracebackAt(frameRow) })
        // The file is looked up off the main thread; the event follows.
        val event = waitFor("the open event") { vm.events.value.lastOrNull() as? TerminalEvent.OpenInEditor }
        assertEquals(File(dir, "boom.py").canonicalFile, event.file)
        assertEquals(2, event.line)
        // The "Traceback (most recent call last):" line is not a place in a file.
        val headerRow = vm.rowOf("most recent call last")
        assertEquals(false, onMain { vm.openTracebackAt(headerRow) })
    }

    @Test
    fun theLastOutputBeforeExitIsNeverLost() {
        val vm = run(
            "burst.py",
            """
            for i in range(3000):
                print("line", i)
            print("THE END")
            """,
        )
        vm.await(footer(0))
        val text = onMain { vm.transcript() }
        assertTrue(text.takeLast(400), text.contains("THE END\n" + footer(0)))
    }

    @Test
    fun aRunThatStopsOnAMissingModuleOffersItsPipPackage() {
        val vm = run("cam.py", "import cv2")
        vm.await("No module named 'cv2'")
        vm.await(footer(1))
        assertEquals(
            dev.skyloft.skypython.pip.MissingPackage("cv2", "opencv-python"),
            waitFor("the missing package") { vm.state.value.missing },
        )
        // Run again clears it until the run fails the same way again.
        val ok = run("fine.py", "print('ok')")
        ok.await(footer(0))
        assertEquals(null, onMain { ok.state.value.missing })
    }

    @Test
    fun saveOutputWritesANewFileNextToTheScript() {
        val vm = run("save.py", """print("to keep")""")
        vm.await(footer(0))
        File(dir, "save_output.txt").writeText("older output")
        var saved: File? = null
        onMain { vm.saveOutput { saved = it } }
        val file = waitFor("the saved file") { saved }
        assertEquals(File(dir, "save_output-2.txt").absoluteFile, file.absoluteFile)
        assertTrue(file.readText().contains("to keep"))
        assertEquals("older output", File(dir, "save_output.txt").readText())
    }

    @Test
    fun picturesTheScriptSavesAreListedWhenItEnds() {
        File(dir, "old.png").apply { writeBytes(byteArrayOf(1, 2, 3)); setLastModified(1_000_000L) }
        // The bundled sample: a PNG written with zlib + struct only.
        val sample = app.assets.open("samples/09_draw_image.py").bufferedReader().readText()
        val vm = run("draw.py", sample)
        vm.await("Saved mandelbrot.png")
        vm.await(footer(0))
        val images = waitFor("the new picture") { vm.state.value.images.takeIf { it.isNotEmpty() } }
        assertEquals(listOf(File(dir, "mandelbrot.png").absoluteFile), images.map { it.absoluteFile })
        // A real PNG Android can decode.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(images.single().path, bounds)
        assertEquals(320, bounds.outWidth)
        assertEquals(240, bounds.outHeight)
        // Run again clears the list until the run is over.
        val plain = run("plain.py", "print('no pictures')")
        plain.await(footer(0))
        Thread.sleep(500)
        assertTrue(onMain { plain.state.value.images }.isEmpty())
    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
    }
}
