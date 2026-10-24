package dev.skyloft.skypython.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces editor text changes so the full text is only materialised once typing pauses.
 * [flush] delivers any pending change immediately (call before save / run / tab switch).
 */
class TextChangeDebouncer(
    private val scope: CoroutineScope,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val emit: (id: String, text: String) -> Unit,
) {
    private var pendingId: String? = null
    private var pendingText: (() -> String)? = null
    private var timer: Job? = null

    /**
     * Doc [id] changed; [text] produces its current full text (it must keep reading *that* doc's
     * content, e.g. capture the doc's Content object rather than the editor view). A pending
     * change of another doc is delivered first.
     */
    fun onChanged(id: String, text: () -> String) {
        if (pendingId != null && pendingId != id) flush()
        pendingId = id
        pendingText = text
        timer?.cancel()
        timer = scope.launch {
            delay(delayMs)
            flush()
        }
    }

    /** Delivers the pending change now, if any. Call on the same thread as [onChanged]. */
    fun flush() {
        val id = pendingId ?: return
        val text = pendingText ?: return
        pendingId = null
        pendingText = null
        timer?.cancel()
        timer = null
        emit(id, text())
    }

    companion object {
        const val DEFAULT_DELAY_MS = 300L
    }
}
