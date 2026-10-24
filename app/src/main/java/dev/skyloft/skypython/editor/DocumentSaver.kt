package dev.skyloft.skypython.editor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Writes dirty documents of [docs]. [docs] is only touched on the caller's thread;
 * [write] runs on [io].
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
    suspend fun saveAll(): Boolean {
        var ok = true
        for (doc in docs.docs) if (doc.dirty) ok = saveOne(doc) && ok
        return ok
    }

    /** Writes [doc]'s current text; returns false (and reports) on failure. */
    suspend fun save(doc: OpenDocument): Boolean = saveOne(doc)

    private suspend fun saveOne(doc: OpenDocument): Boolean = mutex.withLock {
        val text = docs.find(doc.id)?.text ?: return@withLock true
        val result = withContext(io) { runCatching { write(doc.source, text) } }
        result.onSuccess { docs.markSaved(doc.id, text); onSaved() }
            .onFailure { onError(doc, it) }
        result.isSuccess
    }
}
