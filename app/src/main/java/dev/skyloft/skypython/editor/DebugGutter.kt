package dev.skyloft.skypython.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dev.skyloft.skypython.debug.LineEdit
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorMotionEvent
import io.github.rosemoe.sora.event.InterceptTarget
import io.github.rosemoe.sora.event.LongPressEvent
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * What the debugger shows in the editor for the shown document: breakpoint dots in the gutter
 * and the line the program is paused on. Lines are 1-based. With [onGutterTap] set, a tap on
 * the line numbers toggles a breakpoint instead of moving the cursor, and a long press
 * ([onGutterLongPress]) edits its condition.
 */
data class EditorDebugMarks(
    val breakpoints: Set<Int> = emptySet(),
    /** Breakpoints with a condition: drawn as a ring. */
    val conditional: Set<Int> = emptySet(),
    val executionLine: Int? = null,
    /** ARGB colours, from the Compose theme. */
    val breakpointColor: Int = 0,
    val executionColor: Int = 0,
    val onGutterTap: ((line: Int) -> Unit)? = null,
    val onGutterLongPress: ((line: Int) -> Unit)? = null,
    /** Lines added or removed in the shown document, so breakpoints can follow their code. */
    val onLinesEdited: ((LineEdit) -> Unit)? = null,
) {
    companion object {
        val None = EditorDebugMarks()
    }
}

/**
 * Sora's [CodeEditor] plus the debugger's [marks], painted after the editor itself over a lane
 * left of the line numbers (so highlighting and the analyzer's line styles are untouched).
 */
internal class DebugAwareCodeEditor(context: Context) : CodeEditor(context) {

    var marks: EditorDebugMarks = EditorDebugMarks.None
        set(value) {
            val redraw = field.breakpoints != value.breakpoints || field.conditional != value.conditional ||
                field.executionLine != value.executionLine || field.breakpointColor != value.breakpointColor ||
                field.executionColor != value.executionColor
            field = value
            if (value.onGutterTap != null && lineNumberMarginLeft < LANE_DP * dpUnit) {
                lineNumberMarginLeft = LANE_DP * dpUnit
            }
            if (redraw) invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()

    init {
        subscribeAlways(ClickEvent::class.java) { e ->
            val tap = marks.onGutterTap
            if (tap != null && e.inGutter()) {
                tap(e.line + 1)
                e.intercept(InterceptTarget.TARGET_EDITOR)
            }
        }
        subscribeAlways(LongPressEvent::class.java) { e ->
            val press = marks.onGutterLongPress
            if (press != null && e.inGutter()) {
                press(e.line + 1)
                e.intercept(InterceptTarget.TARGET_EDITOR)
            }
        }
        subscribeAlways(ContentChangeEvent::class.java) { e ->
            val edited = marks.onLinesEdited ?: return@subscribeAlways
            val start = e.changeStart
            val end = e.changeEnd
            if (start.line == end.line) return@subscribeAlways
            when (e.action) {
                ContentChangeEvent.ACTION_INSERT -> edited(LineEdit.Insert(start.line, start.column, end.line))
                ContentChangeEvent.ACTION_DELETE -> edited(LineEdit.Delete(start.line, start.column, end.line))
            }
        }
    }

    private fun EditorMotionEvent.inGutter() =
        motionRegion == EditorMotionEvent.REGION_LINE_NUMBER || motionRegion == EditorMotionEvent.REGION_SIDE_ICON

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Sora scrolls the View itself (scrollY = offsetY), so the canvas arrives translated by
        // the scroll; getCharOffsetY is in visible coordinates. Undo the translation (as Sora's
        // renderer does), or the marks land offsetY px above their lines.
        val saved = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        try {
            drawMarks(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun drawMarks(canvas: Canvas) {
        val m = marks
        if (m.breakpoints.isEmpty() && m.executionLine == null) return
        val content = text
        val lines = content.lineCount
        if (lines == 0) return
        val first = firstVisibleLine.coerceIn(0, lines - 1)
        val last = lastVisibleLine.coerceIn(0, lines - 1)
        val row = rowHeight.toFloat()
        // The gutter scrolls with the text unless line numbers are pinned (never with word wrap).
        val gutterX = if (isLineNumberPinned && !isWordwrap) 0f else -offsetX.toFloat()
        val lane = lineNumberMarginLeft
        val cx = gutterX + lane / 2f
        val radius = minOf(lane / 2f - dpUnit, row * 0.3f).coerceAtLeast(2f * dpUnit)

        m.executionLine?.let { line ->
            val l = line - 1
            if (l in first..last) {
                val top = getCharOffsetY(l, 0) - row
                val bottom = getCharOffsetY(l, content.getColumnCount(l))
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(m.executionColor, 0x38)
                canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
            }
        }
        // No gutter without line numbers (a setting): only the paused line's highlight is shown.
        if (!isLineNumberEnabled) return
        for (line in m.breakpoints) {
            val l = line - 1
            if (l !in first..last) continue
            val cy = getCharOffsetY(l, 0) - row / 2f
            paint.color = m.breakpointColor
            if (line in m.conditional) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = radius * 0.55f
                canvas.drawCircle(cx, cy, radius - paint.strokeWidth / 2f, paint)
            } else {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }
        m.executionLine?.let { line ->
            val l = line - 1
            if (l in first..last) {
                val cy = getCharOffsetY(l, 0) - row / 2f
                val h = radius * 0.95f
                arrow.reset()
                arrow.moveTo(cx - h * 0.8f, cy - h)
                arrow.lineTo(cx + h, cy)
                arrow.lineTo(cx - h * 0.8f, cy + h)
                arrow.close()
                paint.style = Paint.Style.FILL
                paint.color = m.executionColor
                canvas.drawPath(arrow, paint)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        /** Width of the breakpoint lane left of the line numbers. */
        const val LANE_DP = 16f
    }
}
