package dev.skyloft.skypython.lang

import java.io.File
import java.io.IOException

/** Where "Go to definition" leads. */
sealed interface NavTarget {
    /** The shown document: select [range]. */
    data class InFile(val range: EditorRange) : NavTarget

    /**
     * Another file: open it (a tab) and select [range]. [readOnly] for library code and anything
     * outside the projects folder: browsing it must not change Python's own files.
     */
    data class OtherFile(val file: File, val range: EditorRange, val readOnly: Boolean) : NavTarget
}

object Navigation {

    /**
     * Where [d] opens; null when it has no file to show. [projectsRoot] (and [moreRoots], e.g. a
     * project opened from the phone's storage) hold the user's code, which opens editable.
     */
    fun target(d: Definition, projectsRoot: File, vararg moreRoots: File): NavTarget? {
        val line = (d.line - 1).coerceAtLeast(0)
        val range = EditorRange(line, d.column.coerceAtLeast(0), line, d.endColumn.coerceAtLeast(d.column).coerceAtLeast(0))
        if (d.inFile) return NavTarget.InFile(range)
        val path = d.path ?: return null
        val file = File(path)
        val editable = d.origin == Origin.PROJECT && (isUnder(file, projectsRoot) || moreRoots.any { isUnder(file, it) })
        return NavTarget.OtherFile(file, range, readOnly = !editable)
    }

    private fun isUnder(file: File, root: File): Boolean {
        val f = canonical(file)
        val r = canonical(root).trimEnd(File.separatorChar) + File.separator
        return f.startsWith(r)
    }

    private fun canonical(f: File): String = try {
        f.canonicalPath
    } catch (e: IOException) {
        f.absolutePath
    }
}

/** The floating parameter hint shown while typing a call. */
object SignatureHints {

    /** [text] such as `print(*values, sep=' ')`; [boldStart]..[boldEnd] is the current parameter. */
    data class Hint(val text: String, val boldStart: Int, val boldEnd: Int)

    fun hint(sig: Signature): Hint {
        val params = sig.short.takeIf { it.size == sig.params.size || sig.params.isEmpty() }?.takeIf { it.isNotEmpty() }
            ?: sig.params
        val sb = StringBuilder(sig.name).append('(')
        var boldStart = 0
        var boldEnd = 0
        params.forEachIndexed { i, p ->
            if (i > 0) sb.append(", ")
            if (i == sig.index) boldStart = sb.length
            sb.append(p)
            if (i == sig.index) boldEnd = sb.length
        }
        sb.append(')')
        return Hint(sb.toString(), boldStart, boldEnd)
    }

    /**
     * Whether the cursor (the end of [before], the text before it — a few lines are enough) may
     * be inside a call's parentheses: an unclosed `(` outside strings and comments. A cheap
     * filter before asking the server, so the hint hides at once after `)`.
     */
    fun mayBeInCall(before: CharSequence): Boolean {
        val stack = ArrayList<Char>()
        var quote: String? = null
        var i = 0
        val n = before.length
        while (i < n) {
            val c = before[i]
            if (quote != null) {
                when {
                    c == '\\' -> i++
                    before.startsWith(quote, i) -> { i += quote.length - 1; quote = null }
                    c == '\n' && quote.length == 1 -> quote = null // unterminated one-line string
                }
            } else when (c) {
                '#' -> while (i + 1 < n && before[i + 1] != '\n') i++
                '"', '\'' -> {
                    val triple = "$c$c$c"
                    quote = if (before.startsWith(triple, i)) triple.also { i += 2 } else c.toString()
                }
                '(', '[', '{' -> stack.add(c)
                ')', ']', '}' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            i++
        }
        return quote == null && '(' in stack
    }

    private fun CharSequence.startsWith(s: String, at: Int): Boolean =
        at + s.length <= length && (s.indices).all { this[at + it] == s[it] }
}

object Outlines {

    data class Row(val item: OutlineItem, val depth: Int)

    /** Depth-first, in source order. */
    fun flatten(items: List<OutlineItem>): List<Row> {
        val out = ArrayList<Row>()
        fun walk(list: List<OutlineItem>, depth: Int) {
            for (item in list) {
                out.add(Row(item, depth))
                walk(item.children, depth + 1)
            }
        }
        walk(items, 0)
        return out
    }

    /** `Class.method` (dotted) of the innermost definition around 1-based [line], or null. */
    fun symbolAt(items: List<OutlineItem>, line: Int): String? {
        val path = ArrayList<String>()
        var level = items
        while (true) {
            val hit = level.lastOrNull { line in it.line..it.endLine } ?: break
            path.add(hit.name)
            level = hit.children
        }
        return path.takeIf { it.isNotEmpty() }?.joinToString(".")
    }
}
