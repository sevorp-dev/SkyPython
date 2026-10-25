package dev.skyloft.skypython.runtime

import java.io.File

/** What a [PythonProcess] runs. */
sealed interface RunCommand {
    /** Working directory of the process. */
    val workingDir: File

    /** `python -u <file>` in the script's directory. */
    data class Script(val file: File) : RunCommand {
        override val workingDir: File get() = file.absoluteFile.parentFile
    }

    /**
     * [file] under the debugger: `python -u <driver> --channel unix:<channel> <file>`, in the
     * script's directory. The program's own stdin / stdout / stderr stay on the pipes; the IDE
     * talks to [driver] (`skydbg.py`, see [DebuggerInstaller]) over the Unix socket [channel].
     */
    data class Debug(val file: File, val driver: File, val channel: File) : RunCommand {
        override val workingDir: File get() = file.absoluteFile.parentFile
    }

    /** The Python REPL (`python -i -q`). */
    data class Interpreter(val cwd: File) : RunCommand {
        override val workingDir: File get() = cwd
    }

    /** `/system/bin/sh -i` with `python`, `python3` and `pip` on PATH. Line mode, no PTY. */
    data class Shell(val cwd: File) : RunCommand {
        override val workingDir: File get() = cwd
    }
}
