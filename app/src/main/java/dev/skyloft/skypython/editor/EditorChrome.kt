package dev.skyloft.skypython.editor

import android.text.InputType
import java.io.File

import dev.skyloft.skypython.lang.Diagnostic
import dev.skyloft.skypython.settings.CodeFontChoice
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.settings.clampFontSize
import kotlin.math.roundToInt

/** Cursor of the live editor: 0-based [line] and [column], plus the document's [lineCount]. */
data class CursorPosition(val line: Int = 0, val column: Int = 0, val lineCount: Int = 1)

/** Tab text: the label, with a `*` suffix while the doc has unsaved changes. */
fun tabTitle(label: String, dirty: Boolean): String = if (dirty) "$label*" else label

/** Top-bar subtitle: the folder a local doc lives in, or a hint for documents opened from other apps. */
fun docSubtitle(
    doc: OpenDocument,
    projectsRoot: File? = null,
    projectsLabel: String = PROJECTS_LABEL,
    externalLabel: String = EXTERNAL_LABEL,
    /** How a folder outside the projects is shown, e.g. "Internal storage/Documents/MyBot". */
    outside: (File) -> String = { it.path },
): String = when (val src = doc.source) {
    is DocSource.Local -> {
        val folder = src.file.parentFile ?: src.file
        val root = projectsRoot?.let(::resolvedFile)
        val relative = root?.let { folder.relativeToOrNull(it) }
        when {
            relative == null || relative.path.startsWith("..") -> outside(folder)
            relative.path.isEmpty() -> projectsLabel
            else -> projectsLabel + "/" + relative.invariantSeparatorsPath
        }
    }
    is DocSource.External -> externalLabel
}

/** How the projects root is named instead of its internal path (English; the UI uses `R.string.projects_label`). */
const val PROJECTS_LABEL = "Projects"

private const val EXTERNAL_LABEL = "External document"

/** What the Sora view needs from [EditorSettings], with the font size kept in the Settings range. */
data class EditorViewConfig(
    val textSizeSp: Float,
    val wordWrap: Boolean,
    val lineNumbers: Boolean,
    val keepScreenOn: Boolean,
    val codeCompletion: Boolean = true,
    val autoClosePairs: Boolean = true,
    val keyboardSuggestions: Boolean = false,
    /** Code typeface: the bundled JetBrains Mono, or the system monospace. */
    val codeFont: CodeFontChoice = CodeFontChoice.JetBrainsMono,
) {
    companion object {
        const val MIN_FONT_SP = 12
        const val MAX_FONT_SP = 24

        fun from(settings: EditorSettings) = EditorViewConfig(
            textSizeSp = settings.fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP).toFloat(),
            wordWrap = settings.wordWrap,
            lineNumbers = settings.lineNumbers,
            keepScreenOn = settings.keepScreenOn,
            codeCompletion = settings.codeCompletion,
            autoClosePairs = settings.autoClosePairs,
            keyboardSuggestions = settings.keyboardSuggestions,
            codeFont = settings.codeFont,
        )
    }
}

/** Status-line cursor text: 1-based "Ln 3, Col 26" from the 0-based [CursorPosition]. */
fun cursorLabel(cursor: CursorPosition, format: String = CURSOR_FORMAT): String =
    String.format(java.util.Locale.ROOT, format, cursor.line + 1, cursor.column + 1)

/** English status-line cursor format (the UI uses `R.string.editor_status_cursor`). */
const val CURSOR_FORMAT = "Ln %1\$d, Col %2\$d"

/** Status-line text for a problem: "Line 3: SyntaxError: '(' was never closed". */
fun problemLabel(d: Diagnostic): String = "Line ${d.line}: ${d.message.lineSequence().first()}"

/** Font size (whole sp, Settings range) to keep after a pinch left the editor at [textSizePx]. */
fun pinchFontSp(textSizePx: Float, spScale: Float): Int = clampFontSize((textSizePx / spScale).roundToInt())

/**
 * Soft-keyboard input type for the editor. Never auto-capitalizes. With suggestions off it asks
 * for no suggestions and uses the visible-password variation: keyboards that ignore
 * NO_SUGGESTIONS (Gboard, Samsung) still never autocorrect or auto-capitalize such a field.
 */
fun editorInputType(keyboardSuggestions: Boolean): Int {
    val base = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    return if (keyboardSuggestions) base
    else base or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
}
