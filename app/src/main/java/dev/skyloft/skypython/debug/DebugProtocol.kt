package dev.skyloft.skypython.debug

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyloft.skypython.runtime.LineTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A frame of the stopped program; [id] is its index (0 = innermost) until it runs again. */
data class StackFrame(
    val id: Int,
    val name: String,
    val file: String,
    val line: Int,
    /** False for module-level code, whose locals are its globals. */
    val hasLocals: Boolean,
    /** Code under the project (not the standard library or installed packages). */
    val user: Boolean,
)

/** A value as the debugger shows it: [value] is a truncated repr; [ref] > 0 can be expanded. */
data class Variable(val name: String, val value: String, val type: String, val ref: Int, val size: Int?)

/** Up to 200 variables; [more] were left out. */
data class VariablesPage(val variables: List<Variable>, val more: Int)

sealed interface EvalResult {
    data class Value(val variable: Variable) : EvalResult
    /** The expression raised (or didn't compile): "NameError: name 'x' is not defined". */
    data class Error(val message: String) : EvalResult
}

data class BreakpointResult(val line: Int, val actualLine: Int, val verified: Boolean, val message: String?)

enum class StopReason {
    BREAKPOINT, STEP, PAUSE, ENTRY, EXCEPTION;

    companion object {
        fun parse(s: String?): StopReason = when (s) {
            "breakpoint" -> BREAKPOINT
            "pause" -> PAUSE
            "entry" -> ENTRY
            "exception" -> EXCEPTION
            else -> STEP
        }
    }
}

sealed interface DebugEvent {
    data class Ready(val pid: Int) : DebugEvent
    data class Stopped(val reason: StopReason, val frames: List<StackFrame>, val exception: String?) : DebugEvent
    data object Continued : DebugEvent
    data class Exited(val code: Int) : DebugEvent
    /** The connection closed: the program ended or was killed. Always the last event. */
    data object Disconnected : DebugEvent
}

/**
 * The debugger driver's protocol (runtime assets/debugger/skydbg.py has the full description):
 * JSON lines; requests `{"id", "method", "params"}`, answers `{"id", "result" | "error"}`,
 * events `{"event": ...}`.
 */
object DebugProtocol {

    sealed interface Incoming {
        data class Event(val event: DebugEvent) : Incoming
        data class Response(val id: Long, val result: JsonElement?, val error: String?) : Incoming
    }

    fun request(id: Long, method: String, params: JsonObject = JsonObject()): String = JsonObject().apply {
        addProperty("id", id)
        addProperty("method", method)
        add("params", params)
    }.toString()

    fun breakpointsParams(file: String, breakpoints: List<Breakpoint>) = JsonObject().apply {
        addProperty("file", file)
        add("breakpoints", JsonArray().apply {
            breakpoints.forEach { bp ->
                add(JsonObject().apply {
                    addProperty("line", bp.line)
                    bp.condition?.takeIf { it.isNotBlank() }?.let { addProperty("condition", it) }
                })
            }
        })
    }

    fun startParams(userRoots: List<String>) = JsonObject().apply {
        addProperty("stopOnEntry", false)
        add("userRoots", JsonArray().apply { userRoots.forEach { add(it) } })
    }

    fun scopeParams(frameId: Int, globals: Boolean) = JsonObject().apply {
        addProperty("frameId", frameId)
        addProperty("scope", if (globals) "globals" else "locals")
    }

    fun refParams(ref: Int) = JsonObject().apply { addProperty("ref", ref) }

    fun evaluateParams(frameId: Int, expression: String) = JsonObject().apply {
        addProperty("frameId", frameId)
        addProperty("expression", expression)
    }

    /** One line from the driver, or null if it isn't a message this client knows. */
    fun parse(line: String): Incoming? {
        val obj = try {
            JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return try {
            val event = obj.str("event")
            if (event != null) {
                Incoming.Event(
                    when (event) {
                        "ready" -> DebugEvent.Ready(obj.int("pid") ?: 0)
                        "stopped" -> DebugEvent.Stopped(
                            StopReason.parse(obj.str("reason")),
                            frames(obj["frames"]),
                            obj.str("exception"),
                        )
                        "continued" -> DebugEvent.Continued
                        "exited" -> DebugEvent.Exited(obj.int("code") ?: 0)
                        else -> return null
                    },
                )
            } else {
                val id = obj["id"]?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
                Incoming.Response(id, obj["result"], obj.str("error"))
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    fun frames(json: JsonElement?): List<StackFrame> =
        json?.takeIf { it.isJsonArray }?.asJsonArray?.map {
            val o = it.asJsonObject
            StackFrame(
                id = o.int("id") ?: 0,
                name = o.str("name").orEmpty(),
                file = o.str("file").orEmpty(),
                line = o.int("line") ?: 0,
                hasLocals = o["hasLocals"]?.asBoolean ?: true,
                user = o["user"]?.asBoolean ?: true,
            )
        }.orEmpty()

    fun variablesPage(json: JsonElement?): VariablesPage {
        val o = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return VariablesPage(emptyList(), 0)
        val list = o["variables"]?.takeIf { it.isJsonArray }?.asJsonArray?.map { variable(it.asJsonObject) }.orEmpty()
        return VariablesPage(list, o.int("more") ?: 0)
    }

    fun evalResult(json: JsonElement?): EvalResult {
        val o = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return EvalResult.Error("no result")
        o.str("error")?.let { return EvalResult.Error(it) }
        return EvalResult.Value(variable(o))
    }

    fun breakpointResults(json: JsonElement?): List<BreakpointResult> =
        json?.takeIf { it.isJsonArray }?.asJsonArray?.map {
            val o = it.asJsonObject
            val line = o.int("line") ?: 0
            BreakpointResult(line, o.int("actualLine") ?: line, o["verified"]?.asBoolean ?: false, o.str("message"))
        }.orEmpty()

    private fun variable(o: JsonObject) = Variable(
        name = o.str("name").orEmpty(),
        value = o.str("value").orEmpty(),
        type = o.str("type").orEmpty(),
        ref = o.int("ref") ?: 0,
        size = o.int("size"),
    )

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.asInt
}

/** Outcome of a request: [Ok] with the result, or [Failed] (an error answer, a timeout, or no connection). */
sealed interface DebugReply {
    data class Ok(val result: JsonElement?) : DebugReply
    data class Failed(val message: String) : DebugReply
}

/**
 * JSON-lines client of the debugger driver over [transport]: numbered requests with replies
 * matched by id, and [events] in the order the driver sent them, ending with
 * [DebugEvent.Disconnected]. Writes run on [io]; a read loop runs in [scope].
 */
class DebugClient(
    private val transport: LineTransport,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<DebugReply>>()
    private val eventChannel = Channel<DebugEvent>(Channel.UNLIMITED)
    @Volatile var isClosed = false
        private set

    val events: Flow<DebugEvent> = eventChannel.receiveAsFlow()

    init {
        scope.launch(io) { readLoop() }
    }

    suspend fun call(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = TIMEOUT_MS): DebugReply {
        if (isClosed) return GONE
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<DebugReply>()
        pending[id] = answer
        try {
            if (isClosed) return GONE
            val sent = withContext(io) {
                try {
                    transport.writeLine(DebugProtocol.request(id, method, params))
                    true
                } catch (e: IOException) {
                    false
                }
            }
            if (!sent) {
                closed()
                return GONE
            }
            return withTimeoutOrNull(timeoutMs) { answer.await() } ?: DebugReply.Failed("timed out")
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        runCatching { transport.close() }
        closed()
    }

    private fun readLoop() {
        try {
            while (true) {
                val line = transport.readLine() ?: break
                when (val msg = DebugProtocol.parse(line)) {
                    is DebugProtocol.Incoming.Event -> eventChannel.trySend(msg.event)
                    is DebugProtocol.Incoming.Response -> pending.remove(msg.id)?.complete(
                        if (msg.error != null) DebugReply.Failed(msg.error) else DebugReply.Ok(msg.result),
                    )
                    null -> Unit
                }
            }
        } catch (e: IOException) {
            // end of stream
        } finally {
            closed()
        }
    }

    private fun closed() {
        if (isClosed) return
        isClosed = true
        for (id in pending.keys.toList()) pending.remove(id)?.complete(GONE)
        eventChannel.trySend(DebugEvent.Disconnected)
        eventChannel.close()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        val GONE = DebugReply.Failed("disconnected")
    }
}
