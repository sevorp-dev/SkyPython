package dev.skyloft.skypython.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * The editor's coroutines wait on the language server, whose answers arrive on its reader
 * thread. Compose's test rule runs composition coroutines on an unconfined test dispatcher, so
 * without [mainConfined] the code after the wait (which touches the editor View) ran on that
 * reader thread: CalledFromWrongThreadException on Android 7.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class MainConfinedTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var reader: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        main = newSingleThreadContext("fake-main")
        reader = newSingleThreadContext("server-reader")
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        reader.close()
    }

    /** Thread the code after an answer from the reader thread runs on, in a scope of [context]. */
    private fun threadAfterServerAnswer(context: CoroutineContext): String = runBlocking {
        val answer = CompletableDeferred<Unit>()
        val after = CompletableDeferred<String>()
        CoroutineScope(context).launch {
            answer.await()
            after.complete(Thread.currentThread().name.substringBefore(" @")) // debug mode adds " @coroutine#n"
        }
        launch(reader) { answer.complete(Unit) }
        withTimeout(5_000) { after.await() }
    }

    @Test
    fun unconfinedHostResumesOnTheServerThread() {
        // The failure mode this guards against (the Compose test rule's effect dispatcher).
        assertEquals("server-reader", threadAfterServerAnswer(UnconfinedTestDispatcher()))
    }

    @Test
    fun mainConfinedResumesOnMainWhateverTheHostDispatcher() {
        assertEquals("fake-main", threadAfterServerAnswer(mainConfined(UnconfinedTestDispatcher())))
    }
}
