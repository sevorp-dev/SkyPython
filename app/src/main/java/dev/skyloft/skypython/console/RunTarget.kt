package dev.skyloft.skypython.console

import java.io.File

/** What the console runs; also what Restart re-runs. */
sealed interface RunTarget {
    /** Console title. */
    val title: String

    data class Script(val file: File) : RunTarget {
        override val title: String get() = file.name
    }

    /** Python REPL (`python -i -q`) in the projects root. */
    data object Interpreter : RunTarget {
        override val title: String get() = "Interpreter"
    }

    /** `/system/bin/sh -i` in the projects root, with `python` / `pip` on PATH. */
    data object Terminal : RunTarget {
        override val title: String get() = "Terminal"
    }
}

private val PYTHON_PROMPTS = setOf(">>> ", "... ")

/**
 * Length of the prompt at the end of a stderr [chunk], 0 if there is none. Python's REPL and
 * the shell write their prompts to stderr without a newline; the console shows them in the
 * normal colour instead of red. A prompt is the whole last line of the chunk (after the last
 * '\n'): exactly `>>> ` or `... ` for Python, or anything ending in `$ ` / `# ` in the
 * terminal. Scripts have no prompts: their stderr is always an error.
 */
fun promptLength(chunk: String, target: RunTarget): Int {
    if (target is RunTarget.Script) return 0
    val last = chunk.substring(chunk.lastIndexOf('\n') + 1)
    val isPrompt = last in PYTHON_PROMPTS ||
        (target is RunTarget.Terminal && (last.endsWith("$ ") || last.endsWith("# ")))
    return if (isPrompt) last.length else 0
}
