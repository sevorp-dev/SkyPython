package dev.skyloft.skypython.editor

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.skyloft.skypython.R
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Sora's selection toolbar (select all / copy / paste / cut) plus "Comment" (`#`) and
 * "Indent" (`⇥`) for the selected lines, and "Go to definition" / "Usages" when the selection is
 * one name (a long-press selects the word under the finger). Comment and Indent show only while
 * text is selected in an editable document; the navigation keys also in read-only library code.
 */
class SkyTextActionWindow(
    private val editor: CodeEditor,
    onComment: () -> Unit,
    onIndent: () -> Unit,
    onGoToDefinition: () -> Unit = {},
    onFindUsages: () -> Unit = {},
    private val navigationEnabled: () -> Boolean = { true },
) : EditorTextActionWindow(editor) {

    private val editKeys: List<TextView>
    private val navKeys: List<ImageView>

    init {
        // panel_root > panel_hv (HorizontalScrollView) > LinearLayout of buttons.
        val row = (view.getChildAt(0) as ViewGroup).getChildAt(0) as LinearLayout
        navKeys = listOf(
            iconKey(R.string.editor_action_go_to_definition, R.drawable.ic_go_to_definition) { dismiss(); onGoToDefinition() },
            iconKey(R.string.editor_action_find_usages, R.drawable.ic_find_usages) { dismiss(); onFindUsages() },
        )
        editKeys = listOf(
            key(R.string.editor_action_comment, "#", onComment),
            key(R.string.editor_action_indent, "⇥", onIndent),
        )
        // Navigation first: on a selected name it is the likely next step.
        navKeys.forEachIndexed { i, v -> row.addView(v, i) }
        editKeys.forEach(row::addView)
    }

    private fun size() = (45 * editor.dpUnit).toInt()

    private fun ripple(v: View) {
        val ripple = TypedValue()
        v.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        if (ripple.resourceId != 0) v.setBackgroundResource(ripple.resourceId)
    }

    private fun iconColor() = editor.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR)

    private fun iconKey(@StringRes description: Int, @DrawableRes icon: Int, action: () -> Unit): ImageView {
        val ctx = editor.context
        val pad = (12 * editor.dpUnit).toInt()
        return ImageView(ctx).apply {
            setImageResource(icon)
            contentDescription = ctx.getString(description)
            setPadding(pad, pad, pad, pad)
            setColorFilter(iconColor())
            ripple(this)
            layoutParams = LinearLayout.LayoutParams(size(), size())
            setOnClickListener { action() }
        }
    }

    private fun key(@StringRes description: Int, label: String, action: () -> Unit): TextView {
        val ctx = editor.context
        return TextView(ctx).apply {
            text = label
            contentDescription = ctx.getString(description)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setTextColor(iconColor())
            ripple(this)
            layoutParams = LinearLayout.LayoutParams(size(), size())
            setOnClickListener { action() }
        }
    }

    /** The selection is one Python name on one line (what a long-press on a name selects). */
    private fun selectionIsName(): Boolean {
        val c = editor.cursor
        if (!c.isSelected || c.leftLine != c.rightLine) return false
        val len = c.rightColumn - c.leftColumn
        if (len !in 1..MAX_NAME) return false
        val s = editor.text.subContent(c.leftLine, c.leftColumn, c.rightLine, c.rightColumn).toString()
        return isPythonName(s)
    }

    override fun displayWindow() {
        val edit = editor.cursor.isSelected && editor.isEditable
        val nav = navigationEnabled() && selectionIsName()
        editKeys.forEach {
            it.visibility = if (edit) View.VISIBLE else View.GONE
            it.setTextColor(iconColor())
        }
        navKeys.forEach {
            it.visibility = if (nav) View.VISIBLE else View.GONE
            it.setColorFilter(iconColor())
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

    private companion object {
        const val MAX_NAME = 100
    }
}

/** The Python name [column] of [line] touches (inside it or right after it), or null. */
fun wordAt(line: String, column: Int): String? {
    if (column < 0 || column > line.length) return null
    fun isNameChar(c: Char) = c == '_' || Character.isLetterOrDigit(c)
    var start = column
    while (start > 0 && isNameChar(line[start - 1])) start--
    var end = column
    while (end < line.length && isNameChar(line[end])) end++
    return line.substring(start, end).takeIf(::isPythonName)
}

/** A Python identifier:a letter or `_`, then letters, digits or `_` (Unicode letters too). */
fun isPythonName(s: String): Boolean =
    s.isNotEmpty() && (s[0] == '_' || Character.isLetter(s[0])) && s.all { it == '_' || Character.isLetterOrDigit(it) }
