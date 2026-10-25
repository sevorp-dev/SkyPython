package dev.skyloft.skypython.update

/** A piece of release notes, shown as plain styled text. */
sealed interface NoteBlock {
    data class Heading(val level: Int, val text: String) : NoteBlock
    data class Bullet(val text: String, val marker: String = "•") : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
    /** A fenced code block, verbatim. */
    data class Code(val text: String) : NoteBlock
}

/** Just enough Markdown for the files in `.github/release-notes`: headings, lists, paragraphs. */
object ReleaseNotes {
    private const val NL = "\n"
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
    private val BULLET = Regex("""^[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\d{1,3}[.)])\s+(.*)$""")
    private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val HTML = Regex("""<[^>]+>""")
    private val EMPHASIS = Regex("""(\*\*|\*|`)(.+?)\1""")
    // Only around words: x86_64 and snake_case names keep their underscores.
    private val UNDERSCORES = Regex("""(?<!\w)(__|_)(.+?)\1(?!\w)""")

    fun parse(markdown: String): List<NoteBlock> {
        val out = mutableListOf<NoteBlock>()
        val para = StringBuilder()
        // The bullet being read: continuation lines (indented) join it.
        var bullet: NoteBlock.Bullet? = null

        fun flush() {
            bullet?.let { out += it }
            bullet = null
            if (para.isNotBlank()) out += NoteBlock.Paragraph(para.toString().trim())
            para.clear()
        }

        // Lines of the open ``` block, or null outside one.
        var code: MutableList<String>? = null

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                val open = code
                if (open == null) {
                    flush()
                    code = mutableListOf()
                } else {
                    if (open.isNotEmpty()) out += NoteBlock.Code(open.joinToString(NL))
                    code = null
                }
                continue
            }
            code?.let {
                it += line
                continue
            }
            if (trimmed.isEmpty() || RULE.matches(trimmed)) {
                flush()
                continue
            }
            // HTML blocks (centered images, badges) say nothing as text.
            if (trimmed.startsWith("<")) continue
            val heading = HEADING.matchEntire(trimmed)
            val b = BULLET.matchEntire(trimmed)
            val n = NUMBERED.matchEntire(trimmed)
            when {
                heading != null -> {
                    flush()
                    out += NoteBlock.Heading(heading.groupValues[1].length, inline(heading.groupValues[2]))
                }
                b != null -> {
                    flush()
                    bullet = NoteBlock.Bullet(inline(b.groupValues[1]))
                }
                n != null -> {
                    flush()
                    bullet = NoteBlock.Bullet(inline(n.groupValues[2]), marker = n.groupValues[1])
                }
                else -> {
                    val text = inline(trimmed)
                    val open = bullet
                    when {
                        text.isEmpty() -> Unit
                        open != null -> bullet = open.copy(text = "${open.text} $text")
                        else -> para.append(if (para.isEmpty()) text else " $text")
                    }
                }
            }
        }
        flush()
        code?.takeIf { it.isNotEmpty() }?.let { out += NoteBlock.Code(it.joinToString(NL)) }
        return out
    }

    private fun inline(s: String): String {
        var t = IMAGE.replace(s, "")
        t = LINK.replace(t) { it.groupValues[1] }
        t = HTML.replace(t, "")
        // Twice: `**bold _and_ italic**`.
        repeat(2) {
            t = EMPHASIS.replace(t) { it.groupValues[2] }
            t = UNDERSCORES.replace(t) { it.groupValues[2] }
        }
        return t.trim()
    }
}
