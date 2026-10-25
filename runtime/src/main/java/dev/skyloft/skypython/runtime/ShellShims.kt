package dev.skyloft.skypython.runtime

import java.io.File
import java.io.IOException

/**
 * Makes `python`, `python3`, `pip` and `pip3` work in the terminal.
 *
 * They are shell functions in a start-up file (`$ENV`, read by Android's mksh for interactive
 * shells), not executable scripts: Android 10+ refuses to exec files from app storage. The
 * functions call the launcher by absolute path, which changes on app updates, so [install]
 * rewrites a stale file.
 */
object ShellShims {

    fun rcFile(paths: PythonPaths): File = File(paths.homeDir, ".skypythonrc")

    fun rc(launcher: File): String {
        val l = shellQuote(launcher.path)
        return buildString {
            append("# Written by SkyPython on every terminal start.\n")
            append("python() { $l \"\$@\"; }\n")
            append("python3() { $l \"\$@\"; }\n")
            // pip may not be installed yet (the Pip screen bootstraps it): do it here, offline.
            append("_sky_pip() { $l -c 'import pip' 2>/dev/null || ")
            append("$l -m ensurepip --user --default-pip >/dev/null || return; $l -m pip \"\$@\"; }\n")
            append("pip() { _sky_pip \"\$@\"; }\n")
            append("pip3() { _sky_pip \"\$@\"; }\n")
            append("PS1='\${PWD##*/} \$ '\n")
        }
    }

    /** Writes the start-up file at [rcFile] (its folder is created if missing). */
    fun install(rcFile: File, launcher: File) {
        val dir = rcFile.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val content = rc(launcher)
        if (!rcFile.isFile || rcFile.readText() != content) rcFile.writeText(content)
    }

    /** Drops the warnings an interactive shell prints when it has no terminal (no PTY yet). */
    fun stripNoise(chunk: String): String =
        chunk.split('\n').let { lines ->
            lines.filterIndexed { i, line ->
                // Keep the trailing piece (a prompt or partial line); drop only complete noise lines.
                i == lines.lastIndex || NOISE.none { line.endsWith(it) }
            }.joinToString("\n")
        }

    private val NOISE = listOf(
        "can't find tty fd: No such device or address",
        "warning: won't have full job control",
    )

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
