package dev.skyloft.skypython.editor

import java.io.File

import dev.skyloft.skypython.settings.EditorSettings

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
): String = when (val src = doc.source) {
    is DocSource.Local -> {
        val folder = src.file.parentFile ?: src.file
        val root = projectsRoot?.let(::resolvedFile)
        val relative = root?.let { folder.relativeToOrNull(it) }
        when {
            relative == null || relative.path.startsWith("..") -> folder.path
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
) {
    companion object {
        const val MIN_FONT_SP = 12
        const val MAX_FONT_SP = 24

        fun from(settings: EditorSettings) = EditorViewConfig(
            textSizeSp = settings.fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP).toFloat(),
            wordWrap = settings.wordWrap,
            lineNumbers = settings.lineNumbers,
            keepScreenOn = settings.keepScreenOn,
        )
    }
}
