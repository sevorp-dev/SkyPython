package dev.skyloft.skypython.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on every CI API level, so the API 24/25 fallbacks are exercised on a real device. */
@RunWith(AndroidJUnit4::class)
class ProcessCompatDeviceTest {

    private fun shell(script: String): Process =
        ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()

    @Test
    fun waitForReportsExit() {
        val p = shell("exit 3")
        assertTrue(ProcessCompat.waitFor(p, 10_000))
        assertFalse(ProcessCompat.isAlive(p))
    }

    @Test
    fun destroyForciblyKillsProcessThatIgnoresSigterm() {
        val p = shell("trap '' TERM; echo ready; while :; do sleep 1; done")
        try {
            // Wait until the trap is installed.
            p.inputStream.bufferedReader().readLine()
            assertTrue(ProcessCompat.isAlive(p))
            assertFalse("still running after a short wait", ProcessCompat.waitFor(p, 200))

            ProcessCompat.destroyForcibly(p)

            assertTrue("process survived destroyForcibly", ProcessCompat.waitFor(p, 5_000))
            assertFalse(ProcessCompat.isAlive(p))
        } finally {
            p.destroy()
        }
    }
}
