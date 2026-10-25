package dev.skyloft.skypython.lang

import com.google.gson.JsonObject
import dev.skyloft.skypython.runtime.LineTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-lines client of the language server over [transport]: numbered requests, replies
 * matched by id, timeouts, and a `cancel` message for every request its caller gave up on
 * (timeout or coroutine cancellation), so the server can skip stale work.
 *
 * Never blocks the caller's thread: writes run on [io], replies are read by a loop in [scope].
 */
class LanguageClient(
    private val transport: LineTransport,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** When the server last said anything ([now] ms): a busy server still answers "cancelled". */
    @Volatile var lastMessageAt: Long = now()
        private set

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Reply>>()
    private val ready = CompletableDeferred<Unit>()
    private val timeouts = AtomicInteger(0)

    @Volatile var isClosed = false
        private set

    val isReady: Boolean get() = ready.isCompleted && !isClosed

    /** Timeouts in a row; a run of them means the server is stuck. Reset by any answer. */
    val consecutiveTimeouts: Int get() = timeouts.get()

    init {
        scope.launch(io) { readLoop() }
    }

    /** Waits up to [timeoutMs] for the server's `ready` event. False if it died or took too long. */
    suspend fun awaitReady(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { runCatching { ready.await() }.isSuccess } == true && !isClosed

    suspend fun call(method: String, params: JsonObject, timeoutMs: Long): Reply {
        if (isClosed) return Reply.Gone
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<Reply>()
        pending[id] = answer
        var answered = false
        try {
            if (isClosed) return Reply.Gone // closed between the first check and registering
            val sent = withContext(io) {
                try {
                    transport.writeLine(Protocol.request(id, method, params))
                    true
                } catch (e: IOException) {
                    false
                }
            }
            if (!sent) {
                closed()
                return Reply.Gone
            }
            val reply = withTimeoutOrNull(timeoutMs) { answer.await() }
            answered = reply != null
            if (reply == null) {
                timeouts.incrementAndGet()
                return Reply.TimedOut
            }
            if (reply is Reply.Ok || reply is Reply.Failed) timeouts.set(0)
            return reply
        } finally {
            pending.remove(id)
            // Timed out or the caller was cancelled: tell the server, without waiting.
            if (!answered && !isClosed) sendCancel(id)
        }
    }

    /** Completions at [line] (1-based) / [column] (0-based, UTF-16), or null if unavailable. */
    suspend fun complete(source: String, line: Int, column: Int, path: String?, timeoutMs: Long): List<Completion>? {
        val params = JsonObject().apply {
            addProperty("source", source)
            addProperty("line", line)
            addProperty("column", column)
            if (path != null) addProperty("path", path)
        }
        return (call("complete", params, timeoutMs) as? Reply.Ok)?.let { Protocol.completions(it.result) }
    }

    /** Problems in [source], or null if unavailable. */
    suspend fun diagnose(source: String, path: String?, timeoutMs: Long): List<Diagnostic>? {
        val params = JsonObject().apply {
            addProperty("source", source)
            if (path != null) addProperty("path", path)
        }
        return (call("diagnose", params, timeoutMs) as? Reply.Ok)?.let { Protocol.diagnostics(it.result) }
    }

    /** Stops the server and fails everything pending. */
    fun close() {
        closed()
        runCatching { transport.close() }
    }

    private fun sendCancel(id: Long) {
        scope.launch(io) {
            try {
                transport.writeLine(Protocol.cancel(id))
            } catch (e: IOException) {
                closed()
            }
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                val line = transport.readLine() ?: break
                lastMessageAt = now()
                when (val msg = Protocol.parse(line)) {
                    Protocol.Incoming.Ready -> ready.complete(Unit)
                    is Protocol.Incoming.Response -> pending.remove(msg.id)?.complete(msg.reply)
                    null -> Unit
                }
            }
        } catch (e: IOException) {
            // treated as end of stream
        } finally {
            closed()
        }
    }

    private fun closed() {
        if (isClosed) return
        isClosed = true
        ready.completeExceptionally(IOException("language server stopped"))
        for (id in pending.keys.toList()) pending.remove(id)?.complete(Reply.Gone)
    }
}
