package dev.skyloft.skypython.lang

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One completion from the server: [name] is the full word, [complete] the part not typed yet. */
data class Completion(val name: String, val complete: String, val type: String)

enum class Severity {
    ERROR, WARNING;

    companion object {
        fun parse(s: String?): Severity = if (s == "error") ERROR else WARNING
    }
}

/**
 * A problem in the code, positioned as the server reports it: [line] / [endLine] 1-based,
 * [column] / [endColumn] 0-based in UTF-16 units (the editor's own column unit).
 */
data class Diagnostic(
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val severity: Severity,
    val message: String,
)

/** Outcome of one request. */
sealed interface Reply {
    data class Ok(val result: JsonElement?) : Reply
    data class Failed(val message: String) : Reply
    /** The server dropped it: cancelled, or superseded by a newer request of the same kind. */
    data object Cancelled : Reply
    data object TimedOut : Reply
    /** The server process is gone (exited, crashed, broken pipe). */
    data object Gone : Reply
}

/** JSON lines of `skylang_server.py` (see its docstring for the protocol). */
internal object Protocol {

    sealed interface Incoming {
        data object Ready : Incoming
        data class Response(val id: Long, val reply: Reply) : Incoming
    }

    fun request(id: Long, method: String, params: JsonObject): String = JsonObject().apply {
        addProperty("id", id)
        addProperty("method", method)
        add("params", params)
    }.toString()

    fun cancel(id: Long): String = JsonObject().apply {
        addProperty("method", "cancel")
        add("params", JsonObject().apply { addProperty("id", id) })
    }.toString()

    /** A line from the server, or null for anything unexpected (stray output is skipped). */
    fun parse(line: String): Incoming? {
        val obj = try {
            JsonParser.parseString(line) as? JsonObject
        } catch (e: RuntimeException) { // JsonParseException, or a malformed number
            null
        } ?: return null
        if (obj.get("event")?.takeIf { it.isJsonPrimitive }?.asString == "ready") return Incoming.Ready
        val idEl = obj.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
        val id = idEl.asLong
        val reply = when {
            obj.has("result") -> Reply.Ok(obj.get("result").takeUnless { it.isJsonNull })
            obj.get("cancelled")?.let { it.isJsonPrimitive && it.asBoolean } == true -> Reply.Cancelled
            obj.has("error") -> Reply.Failed(obj.get("error").let { if (it.isJsonPrimitive) it.asString else it.toString() })
            else -> return null
        }
        return Incoming.Response(id, reply)
    }

    fun completions(result: JsonElement?): List<Completion>? = parseList(result) { o ->
        Completion(
            name = o.get("name").asString,
            complete = o.get("complete")?.asString ?: "",
            type = o.get("type")?.asString ?: "",
        )
    }

    fun diagnostics(result: JsonElement?): List<Diagnostic>? = parseList(result) { o ->
        Diagnostic(
            line = o.get("line").asInt,
            column = o.get("column").asInt,
            endLine = o.get("endLine").asInt,
            endColumn = o.get("endColumn").asInt,
            severity = Severity.parse(o.get("severity")?.asString),
            message = o.get("message")?.asString ?: "",
        )
    }

    private inline fun <T> parseList(result: JsonElement?, item: (JsonObject) -> T): List<T>? {
        val array = result as? JsonArray ?: return null
        return try {
            array.map { item(it.asJsonObject) }
        } catch (e: RuntimeException) { // a missing field, a wrong type: a server bug, not a crash
            null
        }
    }
}
