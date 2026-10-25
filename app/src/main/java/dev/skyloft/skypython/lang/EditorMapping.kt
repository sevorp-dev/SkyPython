package dev.skyloft.skypython.lang

import io.github.rosemoe.sora.lang.completion.CompletionItemKind

/** A span in the editor: 0-based lines, 0-based UTF-16 columns, end exclusive. */
data class EditorRange(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)

/** Server diagnostics → editor positions. */
object DiagnosticsMapping {

    /**
     * Where [d] goes in a document of [lineCount] lines ([lineLength] of a 0-based line).
     * Out-of-range positions are clamped (the text may have changed since the check), and an
     * empty span is widened to one character so the squiggle is visible.
     */
    fun toEditorRange(d: Diagnostic, lineCount: Int, lineLength: (Int) -> Int): EditorRange {
        val lastLine = (lineCount - 1).coerceAtLeast(0)
        val startLine = (d.line - 1).coerceIn(0, lastLine)
        var startCol = d.column.coerceIn(0, lineLength(startLine))
        var endLine = (d.endLine - 1).coerceIn(0, lastLine)
        var endCol = d.endColumn.coerceIn(0, lineLength(endLine))
        if (endLine < startLine || (endLine == startLine && endCol <= startCol)) {
            endLine = startLine
            val len = lineLength(startLine)
            when {
                startCol < len -> endCol = startCol + 1
                len > 0 -> { startCol = len - 1; endCol = len }
                else -> endCol = startCol
            }
        }
        return EditorRange(startLine, startCol, endLine, endCol)
    }

    /** The error to show in the status line: the first error by position, ignoring warnings. */
    fun firstError(diagnostics: List<Diagnostic>): Diagnostic? = diagnostics
        .filter { it.severity == Severity.ERROR }
        .minWithOrNull(compareBy({ it.line }, { it.column }))
}

/** What a completion popup row commits: replace [prefixLength] chars before the cursor. */
data class Proposal(
    val label: String,
    val prefixLength: Int,
    val commitText: String,
    val kind: CompletionItemKind,
    val type: String,
)

/** Server completions → Sora's popup. */
object CompletionMapping {

    fun proposal(c: Completion): Proposal = Proposal(
        label = c.name,
        // jedi's `complete` is the untyped rest of the name; the typed part is replaced whole,
        // so a case-insensitive match ("Up" → "upper") gets the right case too.
        prefixLength = (c.name.length - c.complete.length).coerceAtLeast(0),
        commitText = c.name,
        kind = kind(c.type),
        type = c.type,
    )

    fun kind(type: String): CompletionItemKind = when (type) {
        "module" -> CompletionItemKind.Module
        "class" -> CompletionItemKind.Class
        "function" -> CompletionItemKind.Function
        "keyword" -> CompletionItemKind.Keyword
        "instance", "statement", "param" -> CompletionItemKind.Variable
        "property" -> CompletionItemKind.Property
        "path" -> CompletionItemKind.File
        else -> CompletionItemKind.Text
    }

    /**
     * Whether to ask the server at [column] of [line]: right after part of a name, or after a
     * `.` (attribute access). Not after spaces, operators, numbers or inside a comment, so the
     * popup doesn't flash on every keystroke.
     */
    fun shouldComplete(line: String, column: Int): Boolean {
        if (column <= 0 || column > line.length) return false
        if (inComment(line, column)) return false
        var start = column
        while (start > 0 && isNameChar(line[start - 1])) start--
        val word = line.substring(start, column)
        if (word.isNotEmpty()) return !word[0].isDigit()
        if (line[column - 1] != '.') return false
        // "1." is a float; "x." / ").": attribute access.
        var numStart = column - 1
        while (numStart > 0 && line[numStart - 1].isDigit()) numStart--
        val isNumber = numStart < column - 1 && (numStart == 0 || !isNameChar(line[numStart - 1]))
        return !isNumber
    }

    private fun isNameChar(c: Char) = c == '_' || Character.isLetterOrDigit(c)

    /** A `#` before [column] outside a string literal (single-line view, good enough here). */
    private fun inComment(line: String, column: Int): Boolean {
        var quote: Char? = null
        var i = 0
        while (i < column) {
            val c = line[i]
            when {
                quote != null && c == '\\' -> i++
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == '#' -> return true
            }
            i++
        }
        return false
    }
}
