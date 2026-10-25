package dev.skyloft.skypython.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review finding #3: auto-save runs in viewModelScope; when Back finishes the root activity
 * (API 26–30) onCleared cancels that scope mid-save. Every dirty doc must still be written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSaverTest {

    private val scheduler = TestCoroutineScheduler()
    private val main = StandardTestDispatcher(scheduler, "main")
    private val io = StandardTestDispatcher(scheduler, "io")

    private fun dirtyDocs(n: Int): DocumentsState = DocumentsState().apply {
        repeat(n) { i ->
            val d = open(OpenDocument.local(File("/p/f$i.py"), ""))
            update(d.id, "text $i")
        }
    }

    @Test
    fun saveAllCompletesForEveryDirtyDocEvenIfScopeIsCancelledRightAway() {
        val docs = dirtyDocs(3)
        val written = mutableListOf<String>()
        val saver = DocumentSaver(docs, io, write = { _, text -> written += text }, onSaved = {}, onError = { _, _ -> })
        val scope = CoroutineScope(Job() + main)

        // UNDISPATCHED mirrors Dispatchers.Main.immediate: the save starts synchronously (from ON_STOP)...
        scope.launch(start = CoroutineStart.UNDISPATCHED) { saver.saveAll() }
        // ...and then the ViewModel is cleared.
        scope.cancel()
        scheduler.advanceUntilIdle()

        assertEquals(listOf("text 0", "text 1", "text 2"), written)
        assertTrue(docs.docs.none { it.dirty })
    }

    @Test
    fun saveOneCompletesEvenIfScopeIsCancelled() {
        val docs = dirtyDocs(1)
        val written = mutableListOf<String>()
        val saver = DocumentSaver(docs, io, write = { _, text -> written += text }, onSaved = {}, onError = { _, _ -> })
        val scope = CoroutineScope(Job() + main)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { saver.save(docs.docs.single()) }
        scope.cancel()
        scheduler.advanceUntilIdle()
        assertEquals(listOf("text 0"), written)
        assertFalse(docs.docs.single().dirty)
    }

    @Test
    fun readOnlyDocIsNeverWritten() {
        val docs = DocumentsState()
        val lib = docs.open(OpenDocument.local(java.io.File("/lib/os.py"), "source", readOnly = true))
        val written = mutableListOf<String>()
        val saver = DocumentSaver(docs, io, write = { _, text -> written += text }, onSaved = {}, onError = { _, _ -> })
        var ok: Boolean? = null
        CoroutineScope(Job() + main).launch { ok = saver.save(lib) }
        scheduler.advanceUntilIdle()
        assertEquals(true, ok)
        assertEquals(emptyList<String>(), written)
    }

    @Test
    fun failedWriteIsReportedAndOthersStillSaved() {
        val docs = dirtyDocs(2)
        val written = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val saver = DocumentSaver(
            docs, io,
            write = { _, text -> if (text == "text 0") error("disk full") else written += text },
            onSaved = {},
            onError = { d, e -> errors += "${d.name}: ${e.message}" },
        )
        var ok: Boolean? = null
        CoroutineScope(Job() + main).launch { ok = saver.saveAll() }
        scheduler.advanceUntilIdle()
        assertEquals(false, ok)
        assertEquals(listOf("text 1"), written)
        assertEquals(listOf("f0.py: disk full"), errors)
        assertEquals(listOf(true, false), docs.docs.map { it.dirty })
    }
}
