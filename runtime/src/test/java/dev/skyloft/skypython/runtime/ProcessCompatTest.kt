package dev.skyloft.skypython.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Unit tests run with Build.VERSION.SDK_INT == 0, i.e. the API 24/25 code paths. */
class ProcessCompatTest {

    /** A process that exits when [exit] is called (or on destroy()). */
    private class FakeProcess : Process() {
        @Volatile var code: Int? = null
        var destroyCalls = 0

        fun exit(c: Int) {
            code = c
        }

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (code == null) Thread.sleep(1)
            return code!!
        }
        override fun exitValue(): Int = code ?: throw IllegalThreadStateException("running")
        override fun destroy() {
            destroyCalls++
            code = 143
        }
    }

    @Test
    fun isAliveUntilExited() {
        val p = FakeProcess()
        assertTrue(ProcessCompat.isAlive(p))
        p.exit(0)
        assertFalse(ProcessCompat.isAlive(p))
    }

    @Test
    fun waitForTimesOutWhileRunning() {
        val p = FakeProcess()
        val start = System.nanoTime()
        assertFalse(ProcessCompat.waitFor(p, 100))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("waited $elapsedMs ms", elapsedMs in 90..2_000)
    }

    @Test
    fun waitForReturnsTrueOnceExited() {
        val p = FakeProcess()
        Thread {
            Thread.sleep(50)
            p.exit(3)
        }.start()
        val start = System.nanoTime()
        assertTrue(ProcessCompat.waitFor(p, 5_000))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    @Test
    fun waitForOnExitedProcessReturnsImmediately() {
        val p = FakeProcess().apply { exit(0) }
        assertTrue(ProcessCompat.waitFor(p, 0))
    }

    @Test
    fun interruptWithoutPidReportsFailureAndLeavesTheProcessRunning() {
        val p = FakeProcess()
        assertFalse(ProcessCompat.interrupt(p))
        assertTrue(ProcessCompat.isAlive(p))
        assertEquals(0, p.destroyCalls)
    }

    @Test
    fun destroyForciblyWithoutPidFallsBackToDestroy() {
        val p = FakeProcess()
        ProcessCompat.destroyForcibly(p)
        assertEquals(1, p.destroyCalls)
        assertFalse(ProcessCompat.isAlive(p))
    }
}
