package dev.skyloft.skypython.runtime

import java.io.IOException

/**
 * Pseudo-terminals (native code in `cpp/pty.c`, `libskypty.so`). A program started here sees a
 * real terminal: line editing, colours, window size, and Ctrl+C (byte 3 written to the master)
 * becomes SIGINT for its foreground process group, by the kernel's line discipline.
 */
object Pty {

    init {
        System.loadLibrary("skypty")
    }

    /**
     * Starts [command] in a new session whose controlling terminal is a new PTY of [rows]×[columns].
     * @return the master side's file descriptor; the caller closes it with [close].
     * @throws IOException if the PTY or the process cannot be created. An exec failure (e.g. a
     *   missing program) is not detected here: the child prints it and exits with code 127.
     */
    @Throws(IOException::class)
    fun start(command: PtyCommand, rows: Int, columns: Int, pidOut: IntArray): Int =
        createSubprocess(
            command.executable,
            command.cwd.path,
            command.argv.toTypedArray(),
            command.env.toTypedArray(),
            pidOut,
            rows,
            columns,
            0,
            0,
        )

    /**
     * Low-level [start]: exec's [cmd] (absolute path) with [args] as argv (argv[0] included) and
     * exactly [env] (`NAME=value`) as environment, in [cwd]. Writes the child's pid to `pidOut[0]`.
     * Cell sizes are in pixels (0 if unknown).
     */
    @JvmStatic
    @Throws(IOException::class)
    external fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>,
        pidOut: IntArray,
        rows: Int,
        columns: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Int

    /** Tells the PTY (and so the program, via SIGWINCH) its new size. */
    @JvmStatic
    external fun setWindowSize(fd: Int, rows: Int, columns: Int, cellWidth: Int, cellHeight: Int)

    /**
     * Blocks until [pid] exits. @return its exit code (0..255), or minus the signal that killed it.
     */
    @JvmStatic
    external fun waitFor(pid: Int): Int

    /**
     * kill(2): [pid] > 0 is one process, a negative value the process group `-pid`.
     * @return 0, or the errno.
     */
    @JvmStatic
    external fun sendSignal(pid: Int, signal: Int): Int

    /** The foreground process group of the terminal behind [fd] (tcgetpgrp), or -1. */
    @JvmStatic
    external fun foregroundProcessGroup(fd: Int): Int

    /** close(2). */
    @JvmStatic
    external fun close(fd: Int)

    const val SIGINT = 2
    const val SIGKILL = 9
}
