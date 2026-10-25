package dev.skyloft.skypython.runtime

import android.os.Build

/**
 * [Process.isAlive], [Process.waitFor] with a timeout and [Process.destroyForcibly] for every
 * supported Android version: those three only exist from API 26 (Android 8.0). Below that the
 * same behaviour is built from `exitValue()`, polling and a SIGKILL to the process id.
 * Also signals ([interrupt]) that java.lang.Process has no API for.
 */
internal object ProcessCompat {

    private const val POLL_MS = 10L

    fun isAlive(p: Process): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return p.isAlive
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    /** Waits up to [timeoutMs] for [p] to exit. True if it has exited. */
    fun waitFor(p: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (!isAlive(p)) return true
            val leftMs = (deadline - System.nanoTime()) / 1_000_000
            if (leftMs <= 0) return false
            Thread.sleep(minOf(POLL_MS, leftMs))
        }
    }

    /**
     * Kills [p] with SIGKILL (a script can catch or ignore the SIGTERM of `destroy()`).
     * Android's own `destroyForcibly()` only sends SIGTERM, so on every version the signal is
     * sent to the child's id directly, falling back to the platform call if the id is unreadable.
     */
    fun destroyForcibly(p: Process) {
        val pid = pidOf(p)
        if (pid != null) {
            android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly() else p.destroy()
    }

    /**
     * Sends SIGINT to [p], as Ctrl+C would in a terminal: Python raises KeyboardInterrupt, which a
     * script may catch. False if the signal could not be sent (process id unreadable).
     */
    fun interrupt(p: Process): Boolean = signal(p, SIGINT)

    /** Sends [signal] to [p]'s process id. False if the id is unreadable. */
    fun signal(p: Process, signal: Int): Boolean {
        val pid = pidOf(p) ?: return false
        android.os.Process.sendSignal(pid, signal)
        return true
    }

    /** POSIX signal number of Ctrl+C (the same on every Android ABI). */
    const val SIGINT = 2

    /** The child's process id: java.lang.UNIXProcess keeps it in a private "pid" field. */
    private fun pidOf(p: Process): Int? = try {
        p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
    } catch (e: Exception) {
        null
    }
}
