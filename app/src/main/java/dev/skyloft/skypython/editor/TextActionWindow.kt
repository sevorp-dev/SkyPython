package dev.skyloft.skypython.editor

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.skyloft.skypython.R
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Sora's selection toolbar (select all / copy / paste / cut) plus "Comment" (`#`) and
 * "Indent" (`⇥`) for the selected lines. The extra keys show only while text is selected.
 */
class SkyTextActionWindow(
    private val editor: CodeEditor,
    onComment: () -> Unit,
    onIndent: () -> Unit,
) : EditorTextActionWindow(editor) {

    private val extras: List<TextView>

    init {
        // panel_root > panel_hv (HorizontalScrollView) > LinearLayout of buttons.
        val row = (view.getChildAt(0) as ViewGroup).getChildAt(0) as LinearLayout
        extras = listOf(
            key(R.string.editor_action_comment, "#", onComment),
            key(R.string.editor_action_indent, "⇥", onIndent),
        )
        extras.forEach(row::addView)
    }

    private fun key(description: Int, label: String, action: () -> Unit): TextView {
        val ctx = editor.context
        val size = (45 * editor.dpUnit).toInt()
        return TextView(ctx).apply {
            text = label
            contentDescription = ctx.getString(description)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setTextColor(editor.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR))
            val ripple = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { action() }
        }
    }

    override fun displayWindow() {
        val show = editor.cursor.isSelected && editor.isEditable
        extras.forEach {
            it.visibility = if (show) View.VISIBLE else View.GONE
            it.setTextColor(editor.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR))
        }
        super.displayWindow()
        // Sora caps the panel at 230dp, which would hide the extra keys behind a scroll.
        val root = view
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1_000_000, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(100_000, View.MeasureSpec.AT_MOST),
        )
        val maxWidth = (editor.width - 16 * editor.dpUnit).toInt().coerceAtLeast(width)
        val wanted = root.measuredWidth.coerceAtMost(maxWidth)
        if (wanted > width) setSize(wanted, height)
    }
}
