package dev.skyloft.skypython.console

/** A run of console text that is either all stdout or all stderr. */
data class Segment(val text: String, val isError: Boolean)

/** One display line split into stdout / stderr pieces, ready to draw. Immutable. */
data class ConsoleLine(val pieces: List<Segment> = emptyList()) {
    val length: Int get() = pieces.sumOf { it.text.length }
}

/**
 * Console output kept as display lines, maintained incrementally: text is appended to
 * the last (open) line and '\n' starts a new one, so a snapshot never re-splits the
 * whole buffer. Lines are hard-wrapped at [maxLineLength] chars so one giant line can't
 * become one giant Text. "\r\n" is a newline; a bare '\r' is dropped (no terminal
 * semantics). Once the total exceeds [cap] chars (each line counts its length + 1 for
 * the newline) whole leading lines are dropped.
 *
 * Not thread-safe: confine to one thread/dispatcher.
 */
class ConsoleBuffer(private val cap: Int = 200_000, private val maxLineLength: Int = 1_000) {

    init {
        require(maxLineLength > 0) { "maxLineLength must be positive" }
        require(cap >= maxLineLength) { "cap must be at least maxLineLength" }
    }

    private val done = ArrayDeque<ConsoleLine>()
    private var doneChars = 0

    // The open (last) line: finished pieces plus the piece still being appended to.
    private val openPieces = ArrayList<Segment>()
    private val openText = StringBuilder()
    private var openIsError = false
    private var openLength = 0

    /** Increases on every change; use it to key UI updates (line count may stay constant). */
    var version = 0L
        private set

    /** True when empty or the last char kept was '\n'. */
    var endsWithNewline = true
        private set

    /** Immutable snapshot. Completed lines are shared between snapshots, not copied. */
    val lines: List<ConsoleLine>
        get() {
            val result = ArrayList<ConsoleLine>(done.size + 1)
            result.addAll(done)
            if (openLength > 0) result.add(openLine())
            return result
        }

    fun append(text: String, isError: Boolean) {
        if (text.isEmpty()) return
        var i = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\n' -> {
                    closeLine()
                    endsWithNewline = true
                    i++
                }
                '\r' -> i++
                else -> {
                    if (openLength == maxLineLength) closeLine() // hard wrap
                    val room = maxLineLength - openLength
                    var j = i
                    while (j < n && j - i < room && text[j] != '\n' && text[j] != '\r') j++
                    appendToOpen(text, i, j, isError)
                    endsWithNewline = false
                    i = j
                }
            }
        }
        version++
        trim()
    }

    fun clear() {
        done.clear()
        doneChars = 0
        resetOpen()
        endsWithNewline = true
        version++
    }

    private fun appendToOpen(text: String, from: Int, to: Int, isError: Boolean) {
        if (openText.isNotEmpty() && openIsError != isError) {
            openPieces.add(Segment(openText.toString(), openIsError))
            openText.setLength(0)
        }
        openIsError = isError
        openText.append(text, from, to)
        openLength += to - from
    }

    private fun openLine(): ConsoleLine {
        val pieces = ArrayList<Segment>(openPieces.size + 1)
        pieces.addAll(openPieces)
        if (openText.isNotEmpty()) pieces.add(Segment(openText.toString(), openIsError))
        return ConsoleLine(pieces)
    }

    private fun closeLine() {
        val line = openLine()
        done.addLast(line)
        doneChars += openLength + 1
        resetOpen()
    }

    private fun resetOpen() {
        openPieces.clear()
        openText.setLength(0)
        openLength = 0
    }

    private fun trim() {
        while (doneChars + openLength > cap && done.isNotEmpty()) {
            doneChars -= done.removeFirst().length + 1
        }
    }
}
