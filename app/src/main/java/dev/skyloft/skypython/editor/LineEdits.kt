package dev.skyloft.skypython.editor

/** A selection in 0-based line/column positions; start == end is a plain cursor. */
data class TextSelection(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int) {
    constructor(line: Int, column: Int) : this(line, column, line, column)
}

/**
 * One text change: replace the range (startLine, startColumn)..(endLine, endColumn) with [text],
 * then select [selection]. Applied as a single undo step.
 */
data class LineEdit(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val text: String,
    val selection: TextSelection,
)

const val INDENT = "    "

/**
 * Line-based editing commands (comment, indent, duplicate, delete, move). Pure: they read the
 * document's [lines] and return the edit to apply, or null when there is nothing to do.
 * They act on every line the selection touches; a selection ending at column 0 of a later
 * line does not include that line.
 */
object LineEdits {

    fun toggleComment(lines: List<String>, sel: TextSelection): LineEdit? {
        val (first, last) = lineSpan(lines, sel)
        val block = lines.subList(first, last + 1)
        val code = block.filter { it.isNotBlank() }
        if (code.isEmpty()) return null
        val uncomment = code.all { it.trimStart().startsWith("#") }
        return if (uncomment) {
            transform(lines, sel, first, last) { line ->
                val indent = leadingWhitespace(line)
                if (line.isBlank()) return@transform LineChange.NONE
                val len = if (line.startsWith("# ", indent)) 2 else 1
                LineChange.remove(indent, len)
            }
        } else {
            val at = code.minOf { leadingWhitespace(it) }
            transform(lines, sel, first, last) { line ->
                if (line.isBlank()) LineChange.NONE else LineChange.insert(at, "# ")
            }
        }
    }

    fun indent(lines: List<String>, sel: TextSelection): LineEdit? {
        val (first, last) = lineSpan(lines, sel)
        return transform(lines, sel, first, last) { line ->
            if (line.isEmpty()) LineChange.NONE else LineChange.insert(0, INDENT)
        }
    }

    fun outdent(lines: List<String>, sel: TextSelection): LineEdit? {
        val (first, last) = lineSpan(lines, sel)
        return transform(lines, sel, first, last) { line ->
            when {
                line.startsWith("\t") -> LineChange.remove(0, 1)
                else -> {
                    val n = line.takeWhile { it == ' ' }.length.coerceAtMost(INDENT.length)
                    if (n == 0) LineChange.NONE else LineChange.remove(0, n)
                }
            }
        }
    }

    fun duplicate(lines: List<String>, sel: TextSelection): LineEdit {
        val (first, last) = lineSpan(lines, sel)
        val block = lines.subList(first, last + 1).joinToString("\n")
        val n = last - first + 1
        // Insert "\n<block>" at the end of the last line: works for the document's last line too.
        return LineEdit(
            last, lines[last].length, last, lines[last].length, "\n" + block,
            TextSelection(sel.startLine + n, sel.startColumn, sel.endLine + n, sel.endColumn),
        )
    }

    fun deleteLines(lines: List<String>, sel: TextSelection): LineEdit {
        val (first, last) = lineSpan(lines, sel)
        val col = sel.startColumn
        return when {
            last < lines.lastIndex -> {
                val next = lines[last + 1]
                LineEdit(first, 0, last + 1, 0, "", TextSelection(first, col.coerceAtMost(next.length)))
            }
            first > 0 -> {
                val prev = lines[first - 1]
                LineEdit(first - 1, prev.length, last, lines[last].length, "", TextSelection(first - 1, col.coerceAtMost(prev.length)))
            }
            else -> LineEdit(0, 0, last, lines[last].length, "", TextSelection(0, 0))
        }
    }

    fun moveUp(lines: List<String>, sel: TextSelection): LineEdit? {
        val (first, last) = lineSpan(lines, sel)
        if (first == 0) return null
        val moved = lines.subList(first, last + 1) + lines[first - 1]
        return LineEdit(
            first - 1, 0, last, lines[last].length, moved.joinToString("\n"),
            TextSelection(sel.startLine - 1, sel.startColumn, sel.endLine - 1, sel.endColumn),
        )
    }

    fun moveDown(lines: List<String>, sel: TextSelection): LineEdit? {
        val (first, last) = lineSpan(lines, sel)
        if (last >= lines.lastIndex) return null
        val moved = listOf(lines[last + 1]) + lines.subList(first, last + 1)
        return LineEdit(
            first, 0, last + 1, lines[last + 1].length, moved.joinToString("\n"),
            TextSelection(sel.startLine + 1, sel.startColumn, sel.endLine + 1, sel.endColumn),
        )
    }

    /** First and last line touched by [sel], both inclusive. */
    private fun lineSpan(lines: List<String>, sel: TextSelection): Pair<Int, Int> {
        val first = sel.startLine.coerceIn(0, lines.lastIndex)
        var last = sel.endLine.coerceIn(first, lines.lastIndex)
        if (last > first && sel.endColumn == 0) last--
        return first to last
    }

    private fun leadingWhitespace(line: String): Int = line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) line.length else it }

    /** Insert [text] at [column], or remove [length] chars at [column]; maps cursor columns along. */
    private class LineChange(val column: Int, val removed: Int, val inserted: String) {
        fun apply(line: String) = line.substring(0, column) + inserted + line.substring(column + removed)

        fun mapColumn(col: Int): Int = when {
            removed > 0 && col >= column + removed -> col - removed
            removed > 0 && col > column -> column
            inserted.isNotEmpty() && col >= column -> col + inserted.length
            else -> col
        }

        val isNoOp get() = removed == 0 && inserted.isEmpty()

        companion object {
            val NONE = LineChange(0, 0, "")
            fun insert(column: Int, text: String) = LineChange(column, 0, text)
            fun remove(column: Int, length: Int) = LineChange(column, length, "")
        }
    }

    private fun transform(
        lines: List<String>,
        sel: TextSelection,
        first: Int,
        last: Int,
        change: (String) -> LineChange,
    ): LineEdit? {
        val changes = (first..last).map { change(lines[it]) }
        if (changes.all { it.isNoOp }) return null
        val text = (first..last).joinToString("\n") { changes[it - first].apply(lines[it]) }
        fun map(line: Int, col: Int) = if (line in first..last) changes[line - first].mapColumn(col) else col
        return LineEdit(
            first, 0, last, lines[last].length, text,
            TextSelection(sel.startLine, map(sel.startLine, sel.startColumn), sel.endLine, map(sel.endLine, sel.endColumn)),
        )
    }
}

/** "Go to line" input → 0-based line, clamped to the document; null if it isn't a line number. */
fun parseGoToLine(input: String, lineCount: Int): Int? {
    val n = input.trim().toIntOrNull() ?: return null
    if (n < 0) return null
    return (n - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
}
