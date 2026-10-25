package dev.skyloft.skypython.lang

import dev.skyloft.skypython.runtime.LineTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class LanguageServiceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = CopyOnWriteArrayList<FakeTransport>()
    private var clockMs = 0L
    private var nextTransport: () -> FakeTransport = {
        FakeTransport(onRequest = autoReply(
            "complete" to """[{"name":"json","complete":"on","type":"module"}]""",
            "diagnose" to "[]",
        ))
    }

    private val config = LanguageService.Config(
        completeTimeoutMs = 100,
        diagnoseTimeoutMs = 200,
        readyTimeoutMs = 1_000,
        maxEarlyDeaths = 3,
        earlyDeathMs = 30_000,
        maxConsecutiveTimeouts = 2,
        stuckMs = 30_000,
    )

    private fun service(start: suspend () -> LineTransport = { nextTransport().also { started += it } }) =
        LanguageService(scope, start, config, now = { clockMs })

    @After
    fun tearDown() = scope.cancel()

    private suspend fun LanguageService.awaitReady() = withTimeout(2_000) { while (!isReady) delay(5) }

    @Test
    fun nothingStartsUntilAsked() = runBlocking {
        val s = service()
        delay(50)
        assertEquals(0, started.size)
        s.warmUp()
        s.awaitReady()
        assertEquals(1, started.size)
        s.warmUp() // already running: no second process
        delay(20)
        assertEquals(1, started.size)
    }

    @Test
    fun completionDoesNotWaitForAServerThatIsStillStarting() = runBlocking {
        val s = service()
        // Not started: answers "no server" at once (the editor falls back to plain words)...
        assertNull(s.complete("import js", 1, 9, null))
        // ...but the call started it, so a later keystroke gets real completions.
        s.awaitReady()
        assertEquals(listOf(Completion("json", "on", "module")), s.complete("import js", 1, 9, null))
    }

    @Test
    fun diagnoseWaitsForStartUp() = runBlocking {
        val s = service()
        assertEquals(emptyList<Diagnostic>(), s.diagnose("x = 1\n", null))
        assertEquals(1, started.size)
    }

    @Test
    fun deadServerIsRestartedOnNextUse() = runBlocking {
        val s = service()
        s.warmUp(); s.awaitReady()
        clockMs += 60_000 // lived long enough: not a crash loop
        started.single().die()
        withTimeout(2_000) { while (s.isReady) delay(5) }
        assertEquals(emptyList<Diagnostic>(), s.diagnose("x", null))
        assertEquals(2, started.size)
        assertFalse(s.gaveUp)
    }

    @Test
    fun givesUpAfterRepeatedEarlyDeaths() = runBlocking {
        nextTransport = { FakeTransport().also { it.die() } } // dies right after starting
        val s = service()
        repeat(5) { s.diagnose("x", null) }
        assertEquals(3, started.size)
        assertTrue(s.gaveUp)
        assertNull(s.complete("x", 1, 1, null))
        assertEquals(3, started.size)
    }

    @Test
    fun startFailureCountsAsADeath() = runBlocking {
        var attempts = 0
        val s = service(start = { attempts++; throw IOException("no python") })
        repeat(5) { assertNull(s.diagnose("x", null)) }
        assertEquals(3, attempts)
        assertTrue(s.gaveUp)
    }

    @Test
    fun serverThatNeverGetsReadyIsKilled() = runBlocking {
        nextTransport = { FakeTransport(sendReady = false) }
        val s = service()
        assertNull(s.diagnose("x", null))
        assertTrue(started.single().closed)
    }

    @Test
    fun hungServerIsRestartedAfterRepeatedTimeouts() = runBlocking {
        nextTransport = { FakeTransport() } // ready, but never answers
        val s = service()
        s.warmUp(); s.awaitReady()
        assertNull(s.complete("x", 1, 1, null))
        assertFalse(started.single().closed)
        clockMs += 31_000 // and silent all that time
        assertNull(s.complete("x", 1, 1, null))
        assertTrue(started.single().closed)
        s.warmUp(); s.awaitReady()
        assertEquals(2, started.size)
    }

    @Test
    fun slowServerThatStillTalksIsNotRestarted() = runBlocking {
        nextTransport = { FakeTransport() } // ready, but never answers in time
        val s = service()
        s.warmUp(); s.awaitReady()
        repeat(3) { assertNull(s.complete("x", 1, 1, null)) }
        // Timeouts, but the server spoke recently (clock unchanged since "ready"): keep it.
        assertFalse(started.single().closed)
        assertEquals(1, started.size)
    }

    @Test
    fun shutdownStopsTheServerAndAllowsALaterStart() = runBlocking {
        val s = service()
        s.warmUp(); s.awaitReady()
        s.shutdown()
        assertTrue(started.single().closed)
        assertFalse(s.isReady)
        assertNotNull(s.diagnose("x", null))
        assertEquals(2, started.size)
        assertFalse(s.gaveUp) // a deliberate stop is not a crash
    }
}
