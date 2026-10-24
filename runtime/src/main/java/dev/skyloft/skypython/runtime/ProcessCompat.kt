package dev.skyloft.skypython.runtime

import android.os.Build

/**
 * [Process.isAlive], [Process.waitFor] with a timeout and [Process.destroyForcibly] for every
 * supported Android version: those three only exist from API 26 (Android 8.0). Below that the
 * same behaviour is built from `exitValue()`, polling and a SIGKILL to the process id.
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

    /** Kills [p] with SIGKILL (a script can catch or ignore the SIGTERM of `destroy()`). */
    fun destroyForcibly(p: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            p.destroyForcibly()
            return
        }
        val killed = try {
            // API 24/25: java.lang.UNIXProcess keeps the child's id in a private "pid" field.
            val field = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            android.os.Process.sendSignal(field.getInt(p), android.os.Process.SIGNAL_KILL)
            true
        } catch (e: Exception) {
            false
        }
        if (!killed) p.destroy()
    }
}
