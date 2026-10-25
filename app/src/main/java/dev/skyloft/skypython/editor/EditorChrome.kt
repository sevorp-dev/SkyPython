package dev.skyloft.skypython.editor

import android.text.InputType
import java.io.File

import dev.skyloft.skypython.lang.Diagnostic
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.settings.clampFontSize
import kotlin.math.roundToInt

/** Cursor of the live editor: 0-based [line] and [column], plus the document's [lineCount]. */
data class CursorPosition(val line: Int = 0, val column: Int = 0, val lineCount: Int = 1)

/** Tab text: the label, with a `*` suffix while the doc has unsaved changes. */
fun tabTitle(label: String, dirty: Boolean): String = if (dirty) "$label*" else label

/** Top-bar subtitle: the folder a local doc lives in, or a hint for documents opened from other apps. */
fun docSubtitle(doc: OpenDocument, projectsRoot: File? = null): String = when (val src = doc.source) {
    is DocSource.Local -> {
        val folder = src.file.parentFile ?: src.file
        val root = projectsRoot?.let(::resolvedFile)
        val relative = root?.let { folder.relativeToOrNull(it) }
        when {
            relative == null || relative.path.startsWith("..") -> folder.path
            relative.path.isEmpty() -> PROJECTS_LABEL
            else -> PROJECTS_LABEL + "/" + relative.invariantSeparatorsPath
        }
    }
    is DocSource.External -> "External document"
}

/** How the projects root is named in the UI instead of its internal path. */
const val PROJECTS_LABEL = "Projects"

/** What the Sora view needs from [EditorSettings], with the font size kept in the Settings range. */
data class EditorViewConfig(
    val textSizeSp: Float,
    val wordWrap: Boolean,
    val lineNumbers: Boolean,
    val keepScreenOn: Boolean,
    val codeCompletion: Boolean = true,
    val autoClosePairs: Boolean = true,
    val keyboardSuggestions: Boolean = false,
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
        )
    }
}

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
