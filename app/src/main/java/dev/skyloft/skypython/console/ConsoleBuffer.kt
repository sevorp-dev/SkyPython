package dev.skyloft.skypython.console

/** A run of console text that is either all stdout or all stderr. */
data class Segment(val text: String, val isError: Boolean)

/**
 * Console output kept as kind-tagged segments. Adjacent appends of the same kind are
 * merged; once the total exceeds [cap] chars the oldest chars are dropped.
 * Not thread-safe: confine to one thread/dispatcher.
 */
class ConsoleBuffer(private val cap: Int = 200_000) {

    init {
        require(cap > 0) { "cap must be positive" }
    }

    private class Run(val sb: StringBuilder, val isError: Boolean)

    private val runs = ArrayDeque<Run>()
    private var length = 0

    val segments: List<Segment>
        get() = runs.map { Segment(it.sb.toString(), it.isError) }

    fun append(text: String, isError: Boolean) {
        if (text.isEmpty()) return
        val last = runs.lastOrNull()
        if (last != null && last.isError == isError) {
            last.sb.append(text)
        } else {
            runs.addLast(Run(StringBuilder(text), isError))
        }
        length += text.length
        trim()
    }

    fun clear() {
        runs.clear()
        length = 0
    }

    private fun trim() {
        var excess = length - cap
        while (excess > 0) {
            val first = runs.first()
            val n = first.sb.length
            if (n <= excess) {
                runs.removeFirst()
                excess -= n
                length -= n
            } else {
                first.sb.delete(0, excess)
                length -= excess
                excess = 0
            }
        }
    }
}
