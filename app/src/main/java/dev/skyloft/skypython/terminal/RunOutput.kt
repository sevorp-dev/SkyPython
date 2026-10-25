package dev.skyloft.skypython.terminal

import com.termux.terminal.TerminalBuffer
import dev.skyloft.skypython.share.ShareDirs
import java.io.File
import java.io.IOException

/** The line written under a script's output when it ends. */
object RunFooter {
    private const val DIM = "\u001b[2m"
    private const val RESET = "\u001b[0m"
    private const val HIDE_CURSOR = "\u001b[?25l"

    /** The session's exit status as a shell shows it: a signal `s` (status `-s`) is `128 + s`. */
    fun exitCode(status: Int): Int = if (status < 0) 128 - status else status

    /**
     * `[Program finished, exit code N]` (or its translation, from [label]), dimmed, on a line of
     * its own.
     */
    fun text(
        status: Int,
        atLineStart: Boolean,
        label: (Int) -> String = { "[Program finished, exit code $it]" },
    ): String {
        // The cursor goes too: nothing reads input any more.
        val line = "$DIM${label(exitCode(status))}$RESET\r\n$HIDE_CURSOR"
        return if (atLineStart) line else "\r\n" + line
    }
}

/** Plain text out of the terminal's screen and scrollback. */
object Transcript {
    /**
     * Everything kept, oldest first: lines the terminal wrapped are joined back, colours dropped,
     * trailing blanks and empty lines at the end removed.
     */
    fun of(screen: TerminalBuffer): String =
        screen.transcriptText.lines().joinToString("\n") { it.trimEnd() }.trimEnd()

    /** The whole line [row] belongs to, joining the rows the terminal wrapped it over. */
    fun logicalLine(screen: TerminalBuffer, row: Int): String {
        val top = -screen.activeTranscriptRows
        val bottom = screen.activeRows - screen.activeTranscriptRows - 1
        var first = row
        while (first > top && screen.getLineWrap(first - 1)) first--
        var last = row
        while (last < bottom && screen.getLineWrap(last)) last++
        return screen.getSelectedText(0, first, Int.MAX_VALUE / 2, last).trimEnd()
    }
}

/** `File "…", line N` in a Python traceback: where the editor can jump. */
object Tracebacks {
    data class Location(val path: String, val line: Int)

    private val FRAME = Regex("""File "([^"<>]+)", line (\d+)""")

    fun parse(line: String): Location? {
        val m = FRAME.find(line) ?: return null
        val number = m.groupValues[2].toIntOrNull() ?: return null
        return Location(m.groupValues[1], number)
    }

    /**
     * The file [location] names, if it is an existing file inside [projectsRoot] (the standard
     * library and installed packages are not opened in the editor). Relative paths are relative
     * to [cwd], the folder the program ran in.
     */
    fun resolve(location: Location, cwd: File, projectsRoot: File): File? {
        val named = File(location.path).let { if (it.isAbsolute) it else File(cwd, location.path) }
        val file = runCatching { named.canonicalFile }.getOrNull() ?: return null
        val root = runCatching { projectsRoot.canonicalFile }.getOrNull() ?: return null
        if (!file.isFile) return null
        val inside = file.path.startsWith(root.path + File.separator)
        return if (inside) file else null
    }
}

/** Where "Save output" writes and how "Share output" sends. */
object OutputFiles {
    /** Longer output is shared as a text file: an intent's extras must stay well under 1 MB. */
    const val MAX_SHARE_TEXT = 100_000

    /** `hello.py` → `hello_output.txt`. */
    fun name(source: String): String = source.removeSuffix(".py") + "_output.txt"

    /** [name] in [dir], or `name-2.txt`, `name-3.txt`… if it is taken: earlier output is kept. */
    fun unique(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = File(dir, if (ext.isEmpty()) "$stem-$n" else "$stem-$n.$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /**
     * Writes [text] to a new file [name] in [dir] ([unique]) all at once: it is written to a hidden
     * temporary file first and renamed into place, so the file never shows half-written (to the
     * Files screen, another app, or after the app is killed mid-write). Call off the main thread.
     */
    fun saveNew(dir: File, name: String, text: String): File {
        dir.mkdirs()
        val tmp = File.createTempFile(".$name.", ".tmp", dir)
        try {
            tmp.writeText(text)
            val target = unique(dir, name)
            if (!tmp.renameTo(target)) throw IOException("can't create $target")
            return target
        } finally {
            tmp.delete() // only still there if the write or the rename failed
        }
    }

    fun shareAsFile(text: String): Boolean = text.length > MAX_SHARE_TEXT

    /** Shared output files kept: a receiving app may still be reading an earlier one. */
    private const val SHARES_KEPT = 3

    /**
     * Where "Save output" writes: next to [script] when it is in the projects, else (the
     * interpreter, the shell, or a file opened with the system picker, which runs as a copy in
     * the cache) the projects folder itself. Reads the disk (canonical paths): call off the main thread.
     */
    fun saveDir(script: File?, projectsRoot: File): File {
        val parent = script?.absoluteFile?.parentFile ?: return projectsRoot
        val root = runCatching { projectsRoot.canonicalFile }.getOrNull() ?: return projectsRoot
        val dir = runCatching { parent.canonicalFile }.getOrNull() ?: return projectsRoot
        val inside = dir == root || dir.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)
        return if (inside) parent else projectsRoot
    }

    /**
     * A new file [name] for sharing, in a folder of its own under [shareRoot] (see [ShareDirs]):
     * sharing again never overwrites a file another app may still be reading.
     */
    fun shareFile(shareRoot: File, name: String): File {
        val dir = ShareDirs.newDir(shareRoot)
        ShareDirs.prune(shareRoot, keep = dir, count = SHARES_KEPT)
        return File(dir, name)
    }
}

/** What the terminal screens offer, given what runs. */
data class OutputActions(
    val canInterrupt: Boolean,
    val canStop: Boolean,
    /** A program reads the keyboard: Paste and the soft keyboard make sense. */
    val canType: Boolean,
    val canPaste: Boolean,
    /** A finished script can run again; the interpreter and the shell [canRestart] instead. */
    val canRunAgain: Boolean,
    val canRestart: Boolean,
    val canCopy: Boolean,
    val canShare: Boolean,
    val canSave: Boolean,
    val canClear: Boolean,
) {
    companion object {
        fun of(kind: TerminalKind, running: Boolean, hasOutput: Boolean) = OutputActions(
            canInterrupt = running,
            canStop = running,
            canType = running,
            canPaste = running,
            canRunAgain = kind == TerminalKind.Run && !running,
            canRestart = kind != TerminalKind.Run,
            canCopy = hasOutput,
            canShare = hasOutput,
            canSave = hasOutput,
            canClear = hasOutput,
        )
    }
}
