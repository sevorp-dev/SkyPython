package dev.skyloft.skypython.runtime

import java.io.File
import java.io.IOException

/**
 * A program to start on a pseudo-terminal ([Pty.createSubprocess]): the interpreter or the shell.
 *
 * @property executable absolute path, exec'd directly (no PATH lookup).
 * @property argv full argument vector, `argv[0]` (the name the program sees) included.
 * @property env the child's complete environment as `NAME=value`; nothing else is inherited.
 */
data class PtyCommand(
    val executable: String,
    val argv: List<String>,
    val cwd: File,
    val env: List<String>,
) {
    companion object {
        /** What a full-screen program needs to know about the terminal (the emulator is xterm-like). */
        val TERMINAL_ENV: Map<String, String> = linkedMapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
        )

        /**
         * [command] (interpreter or shell) for a PTY: the same program and variables as the pipe
         * version ([PythonEnvironment]) on top of [appEnv], plus [TERMINAL_ENV]. Pure; see [prepare].
         */
        fun of(
            paths: PythonPaths,
            command: RunCommand,
            appEnv: Map<String, String> = System.getenv(),
        ): PtyCommand {
            require(command !is RunCommand.Script) { "Scripts run on pipes, not in a terminal" }
            val line = PythonEnvironment.command(paths, command)
            val argv0 = if (command is RunCommand.Interpreter) "python3" else File(line[0]).name
            val env = LinkedHashMap(appEnv)
            env.putAll(PythonEnvironment.environment(paths, command))
            env.putAll(TERMINAL_ENV)
            return PtyCommand(
                executable = line[0],
                argv = listOf(argv0) + line.drop(1),
                cwd = command.workingDir,
                env = env.map { (k, v) -> "$k=$v" },
            )
        }

        /** [of], after creating the working directory and (for the shell) writing the rc file. */
        @Throws(IOException::class)
        fun prepare(paths: PythonPaths, command: RunCommand): PtyCommand {
            val cwd = command.workingDir
            if (!cwd.isDirectory && !cwd.mkdirs()) throw IOException("Cannot create $cwd")
            if (command is RunCommand.Shell) ShellShims.install(ShellShims.rcFile(paths), paths.launcher)
            return of(paths, command)
        }
    }
}
