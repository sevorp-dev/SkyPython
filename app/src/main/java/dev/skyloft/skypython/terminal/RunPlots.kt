package dev.skyloft.skypython.terminal

import dev.skyloft.skypython.files.FileKind
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/**
 * Plots a program showed with `plt.show()`: SkyPython's matplotlib backend (runtime
 * `assets/plot/skypython_plot.py`) saves each figure as a PNG and sends the terminal
 * `ESC ] 7777 ; plot ; <path>[;<path>…] BEL`, each path percent-encoded. The terminal hands the
 * text after `7777;` to [TerminalViewModel], which keeps the session's plots
 * ([TerminalState.plots]) and opens the viewer on the new ones.
 */
object RunPlots {
    private const val PLOT = "plot"

    /** At most this many plots are kept per session (a script calling show() in a loop). */
    const val MAX_PLOTS = 50

    /**
     * The pictures of an app command ([text] is what followed `7777;`), or null if it is not a
     * plot command. Paths that are not absolute or not images are dropped.
     */
    fun parse(text: String): List<File>? {
        val parts = text.split(';')
        if (parts.first() != PLOT) return null
        return parts.drop(1).mapNotNull { part ->
            val path = try {
                // quote() never writes '+', so a literal '+' here only comes from %2B.
                URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8.name())
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            path.takeIf { it.startsWith("/") && FileKind.byName(it.substringAfterLast('/')) == FileKind.Image }?.let(::File)
        }
    }

    /**
     * [state] after the app command [text] arrived: the figures it names added to
     * [TerminalState.plots] and the viewer asked to open at the first ([TerminalState.showPlot]).
     * Null when [text] is not a plot command or names no picture (nothing changes).
     */
    fun received(state: TerminalState, text: String): TerminalState? {
        val shown = parse(text)?.takeIf { it.isNotEmpty() } ?: return null
        val plots = add(state.plots, shown)
        return state.copy(plots = plots, showPlot = firstShown(plots, shown) ?: state.showPlot)
    }

    /** [existing] plus the [shown] ones not in it yet, the oldest dropped beyond [MAX_PLOTS]. */
    fun add(existing: List<File>, shown: List<File>): List<File> =
        (existing + shown.filter { it !in existing }.distinct()).takeLast(MAX_PLOTS)

    /**
     * Where the viewer should open after [shown] arrived, in [plots] (the list after [add]): the
     * first of them; null if none of them is in the list.
     */
    fun firstShown(plots: List<File>, shown: List<File>): Int? =
        shown.map { plots.indexOf(it) }.filter { it >= 0 }.minOrNull()

    /** "figure-1.png" → "1"; another name without its extension. What "Figure %s" shows. */
    fun label(file: File): String {
        val stem = file.name.substringBeforeLast('.')
        return stem.removePrefix("figure-").ifEmpty { stem }
    }

    /**
     * Save to project: copies [plot] into [dir] under its own name ("figure-1.png"), or
     * "figure-1-2.png"… when taken (earlier saves are kept). Blocking.
     */
    @Throws(IOException::class)
    fun saveCopy(plot: File, dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val tmp = File.createTempFile(".${plot.name}.", ".tmp", dir)
        try {
            plot.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val target = OutputFiles.unique(dir, plot.name)
            if (!tmp.renameTo(target)) throw IOException("Cannot create $target")
            return target
        } finally {
            tmp.delete()
        }
    }
}
