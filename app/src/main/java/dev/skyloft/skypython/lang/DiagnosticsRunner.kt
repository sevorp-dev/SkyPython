package dev.skyloft.skypython.lang

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Re-checks the shown document once typing pauses for [delayMs]: each [request] cancels the
 * previous one (waiting or already at the server), so only the newest text is diagnosed and
 * only its result reaches [onResult]. Files longer than [maxChars] are not sent at all; their
 * marks are cleared instead (checking them would be slow on weak phones).
 *
 * Call from the main thread; [onResult] is delivered in [scope].
 */
class DiagnosticsRunner(
    private val scope: CoroutineScope,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val maxChars: Int = MAX_CHARS,
    private val diagnose: suspend (source: String, path: String?) -> List<Diagnostic>?,
    private val onResult: (Result) -> Unit,
) {
    /** Diagnostics of [docId] as it was at [version] (the editor's document version). */
    data class Result(val docId: String, val version: Long, val diagnostics: List<Diagnostic>)

    private var job: Job? = null

    /**
     * Doc [docId] is now at [version] with [length] chars; [text] produces that text (read only
     * once the pause is over). [immediate] skips the pause (a document was just opened).
     */
    fun request(
        docId: String,
        version: Long,
        length: Int,
        path: String?,
        immediate: Boolean = false,
        text: () -> String,
    ) {
        job?.cancel()
        job = scope.launch {
            if (!immediate) delay(delayMs)
            if (length > maxChars) {
                onResult(Result(docId, version, emptyList()))
                return@launch
            }
            val diagnostics = diagnose(text(), path) ?: return@launch
            onResult(Result(docId, version, diagnostics))
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        const val DEFAULT_DELAY_MS = 300L
        /** 200 KB of text. */
        const val MAX_CHARS = 200 * 1024
    }
}
