package com.termux.terminal;

import java.io.IOException;

import dev.skyloft.skypython.runtime.Pty;

/**
 * Pseudoterminal subprocesses.
 * <p>
 * Modified for SkyPython: the original declared native methods backed by its own libtermux.so;
 * this version delegates to SkyPython's {@link Pty} (runtime/src/main/cpp/pty.c, libskypty.so),
 * which is built with the app's NDK (16 KB page aligned) and also sends signals.
 */
final class JNI {

    private JNI() {
    }

    /**
     * Create a subprocess on a new pseudoterminal.
     *
     * @param args      argv, argv[0] included
     * @param envVars   the complete environment ("VAR=value")
     * @param processId one-element array receiving the process ID
     * @return the master side's file descriptor; the caller closes it with {@link #close(int)}
     * @throws IOException if the pseudoterminal or the process cannot be created
     */
    static int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns, int cellWidth, int cellHeight) throws IOException {
        return Pty.createSubprocess(cmd, cwd, args, envVars, processId, rows, columns, cellWidth, cellHeight);
    }

    /** Set the window size for a given pty, which allows connected programs to learn how large their screen is. */
    static void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight) {
        Pty.setWindowSize(fd, rows, cols, cellWidth, cellHeight);
    }

    /** @return if >= 0, the exit status of the process. If < 0, the signal causing the process to stop negated. */
    static int waitFor(int processId) {
        return Pty.waitFor(processId);
    }

    /** Close a file descriptor through the close(2) system call. */
    static void close(int fileDescriptor) {
        Pty.close(fileDescriptor);
    }

}
