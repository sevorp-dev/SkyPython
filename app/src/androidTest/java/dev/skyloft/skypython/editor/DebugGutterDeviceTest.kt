package dev.skyloft.skypython.editor

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.MainActivity
import dev.skyloft.skypython.debug.DebugViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Breakpoints in a scrolled editor: a tap on the gutter next to a line far down the file sets
 * the breakpoint on that line, and its dot is drawn on that line, not rows above it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DebugGutterDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var dir: File
    private lateinit var file: File

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun editor(): DebugAwareCodeEditor {
        fun find(v: View): DebugAwareCodeEditor? = when (v) {
            is DebugAwareCodeEditor -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(rule.activity.window.decorView) ?: error("no code editor on screen")
    }

    private fun debugVm() = onMain { ViewModelProvider(rule.activity)[DebugViewModel::class.java] }

    private fun breakpoints(): List<Int> = debugVm().store.state.value[file.path].orEmpty().map { it.line }

    @Before
    fun openLongFile() {
        rule.waitUntilAtLeastOneExists(hasText("hello.py", substring = true), 20_000)
        val projects = File(rule.activity.filesDir, "projects").canonicalFile
        dir = File(projects, "guttertest").apply { mkdirs() }
        // Some long lines too, so word wrap (when on) puts lines on several rows.
        val text = (1..200).joinToString("\n") { i ->
            if (i % 7 == 0) "x$i = '" + "wrap ".repeat(40) + "'" else "x$i = $i"
        } + "\n"
        file = File(dir, "long.py").apply { writeText(text) }.canonicalFile
        val vm = onMain { ViewModelProvider(rule.activity)[EditorViewModel::class.java] }
        onMain { vm.open(file) }
        rule.waitUntil(20_000) { onMain { runCatching { editor().text.toString() == text }.getOrDefault(false) } }
    }

    @After
    fun cleanUp() {
        val debug = debugVm()
        onMain { debug.store.state.value[file.path].orEmpty().forEach { debug.toggleBreakpoint(file.path, it.line) } }
        val vm = onMain { ViewModelProvider(rule.activity)[EditorViewModel::class.java] }
        onMain { vm.closeFile(dir) }
        rule.waitForIdle()
        dir.deleteRecursively()
    }

    /** Screen point (px) in the gutter's breakpoint lane, at the middle of the first row of [line] (0-based). */
    private fun gutterPoint(line: Int): Pair<Float, Float> = onMain {
        val e = editor()
        val at = IntArray(2)
        e.getLocationOnScreen(at)
        (at[0] + e.lineNumberMarginLeft / 2f) to (at[1] + e.getCharOffsetY(line, 0) - e.rowHeight / 2f)
    }

    /** A finger tap on the screen at ([x], [y]). */
    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun aGutterTapFarDownSetsTheBreakpointOnThatLineAndItsDotIsDrawnThere() {
        val target = 150 // 0-based
        onMain {
            val e = editor()
            e.setSelection(target, 0, true)
        }
        rule.waitForIdle()
        Thread.sleep(1_500) // word wrap lays rows out in the background: scroll again once it has
        onMain {
            // The target line in the middle of the view.
            val e = editor()
            val y = (e.getCharOffsetY(target, 0) + e.offsetY - e.height / 2f).toInt()
            e.scroller.forceFinished(true)
            e.scroller.startScroll(0, y, 0, 0, 0)
            e.scroller.abortAnimation()
            e.invalidate()
        }
        rule.waitForIdle()
        assertTrue("scrolled down", onMain { editor().offsetY } > 0)
        assertTrue("line ${target + 1} in view", onMain { editor().getCharOffsetY(target, 0) in 0f..editor().height.toFloat() })
        val (x, y) = gutterPoint(target)
        tap(x, y)
        rule.waitUntil(5_000) { breakpoints().isNotEmpty() }
        assertEquals(listOf(target + 1), breakpoints())

        // The dot: the breakpoint colour at the lane's centre on that row, and not a row above.
        rule.waitForIdle()
        Thread.sleep(500)
        val (color, onRow, rowAbove) = dotPixels(target)
        assertTrue("dot on line ${target + 1}: ${Integer.toHexString(onRow)} vs ${Integer.toHexString(color)}", close(onRow, color))
        assertTrue("no dot a row above", !close(rowAbove, color))
    }

    /** The breakpoint colour and the screen's pixels at the lane centre of [line]'s row and of the row above. */
    private fun dotPixels(line: Int): Triple<Int, Int, Int> {
        val (where, color) = onMain {
            val e = editor()
            val at = IntArray(2)
            e.getLocationOnScreen(at)
            val cx = at[0] + (e.lineNumberMarginLeft / 2f).roundToInt()
            val cy = at[1] + (e.getCharOffsetY(line, 0) - e.rowHeight / 2f).roundToInt()
            Triple(cx, cy, e.rowHeight) to e.marks.breakpointColor
        }
        // What is really on screen (the editor scrolls its View, which only shows on the real
        // hardware-rendered frame, not in a software redraw of the view).
        val shot = instrumentation.uiAutomation.takeScreenshot()
        val (cx, cy, row) = where
        return Triple(color, shot.getPixel(cx, cy), shot.getPixel(cx, cy - row))
    }

    private fun close(a: Int, b: Int): Boolean =
        listOf(16, 8, 0).all { s -> abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)) < 24 }
}
