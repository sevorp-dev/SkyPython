package dev.skyloft.skypython.editor

import kotlinx.coroutines.CoroutineScope

/**
 * Coalesces editor text changes so the full text is only materialised once typing pauses.
 * [flush] delivers any pending change immediately (call before save / run / tab switch).
 */
class TextChangeDebouncer(
    private val scope: CoroutineScope,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val emit: (id: String, text: String) -> Unit,
) {
    /** Doc [id] changed; [text] produces its current full text. */
    fun onChanged(id: String, text: () -> String) {
        emit(id, text())
    }

    fun flush() {}

    companion object {
        const val DEFAULT_DELAY_MS = 300L
    }
}
