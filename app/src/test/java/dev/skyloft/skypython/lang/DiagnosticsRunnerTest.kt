package dev.skyloft.skypython.lang

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsRunnerTest {

    private val scope = TestScope()
    private val calls = mutableListOf<String>()
    private val results = mutableListOf<DiagnosticsRunner.Result>()
    private var materialised = 0
    private val answer = listOf(Diagnostic(1, 0, 1, 1, Severity.ERROR, "boom"))
    private var gate: CompletableDeferred<Unit>? = null

    private val runner = DiagnosticsRunner(
        scope = scope,
        delayMs = 300,
        maxChars = 1_000,
        diagnose = { source, _ ->
            calls += source
            gate?.await()
            answer
        },
        onResult = { results += it },
    )

    private fun edit(doc: String, version: Long, text: String) =
        runner.request(doc, version, text.length, path = "/p/$doc.py") { materialised++; text }

    @Test
    fun burstOfEditsIsDiagnosedOnceAfterThePause() {
        edit("a", 1, "x = (")
        edit("a", 2, "x = (1")
        edit("a", 3, "x = (1)")
        scope.advanceTimeBy(299); scope.runCurrent()
        assertEquals(emptyList<String>(), calls)
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(listOf("x = (1)"), calls)
        assertEquals(1, materialised)
        assertEquals(listOf(DiagnosticsRunner.Result("a", 3, answer)), results)
    }

    @Test
    fun newEditCancelsTheRequestInFlight() {
        gate = CompletableDeferred()
        edit("a", 1, "old")
        scope.advanceTimeBy(301); scope.runCurrent()
        assertEquals(listOf("old"), calls) // in flight, waiting on the server
        edit("a", 2, "new")
        gate!!.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(listOf("old", "new"), calls)
        // Only the newest text's diagnostics reach the editor.
        assertEquals(listOf(DiagnosticsRunner.Result("a", 2, answer)), results)
    }

    @Test
    fun hugeFilesAreNotSentAndTheirMarksAreCleared() {
        runner.request("big", 7, length = 1_001, path = null) { materialised++; "x".repeat(1_001) }
        scope.advanceUntilIdle()
        assertEquals(emptyList<String>(), calls)
        assertEquals(0, materialised)
        assertEquals(listOf(DiagnosticsRunner.Result("big", 7, emptyList())), results)
    }

    @Test
    fun failedDiagnoseKeepsTheOldMarks() {
        val failing = DiagnosticsRunner(scope, 300, 1_000, diagnose = { _, _ -> null }, onResult = { results += it })
        failing.request("a", 1, 1, null) { "x" }
        scope.advanceUntilIdle()
        assertEquals(emptyList<DiagnosticsRunner.Result>(), results)
    }

    @Test
    fun immediateRequestSkipsTheDelay() {
        runner.request("a", 1, 5, null, immediate = true) { "x = 1" }
        scope.runCurrent()
        assertEquals(listOf("x = 1"), calls)
    }

    @Test
    fun cancelDropsPendingWork() {
        edit("a", 1, "x")
        runner.cancel()
        scope.advanceUntilIdle()
        assertEquals(emptyList<String>(), calls)
        assertEquals(emptyList<DiagnosticsRunner.Result>(), results)
    }
}
