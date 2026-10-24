package dev.skyloft.skypython.editor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Writes dirty documents of [docs]. [docs] is only touched on the caller's thread;
 * [write] runs on [io].
 *
 * Saves run [NonCancellable]: auto-save is started from ON_PAUSE/ON_STOP in viewModelScope, and
 * when Back finishes the root activity (API 26–30) onCleared cancels that scope mid-save. A started
 * save therefore always finishes, for every dirty doc.
 */
class DocumentSaver(
    private val docs: DocumentsState,
    private val io: CoroutineDispatcher,
    private val write: (DocSource, String) -> Unit,
    private val onSaved: () -> Unit,
    private val onError: (OpenDocument, Throwable) -> Unit,
) {
    private val mutex = Mutex()

    /** Saves every dirty doc; returns false if any save failed. */
    suspend fun saveAll(): Boolean = withContext(NonCancellable) {
        var ok = true
        for (doc in docs.docs) if (doc.dirty) ok = saveOne(doc) && ok
        ok
    }

    /** Writes [doc]'s current text; returns false (and reports) on failure. */
    suspend fun save(doc: OpenDocument): Boolean = withContext(NonCancellable) { saveOne(doc) }

    private suspend fun saveOne(doc: OpenDocument): Boolean = mutex.withLock {
        val text = docs.find(doc.id)?.text ?: return@withLock true
        val result = withContext(io) { runCatching { write(doc.source, text) } }
        result.onSuccess { docs.markSaved(doc.id, text); onSaved() }
            .onFailure { onError(doc, it) }
        result.isSuccess
    }
}
