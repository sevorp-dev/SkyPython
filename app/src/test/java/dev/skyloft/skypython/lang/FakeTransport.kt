package dev.skyloft.skypython.lang

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyloft.skypython.runtime.LineTransport
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * In-memory stand-in for the server process. Lines the client writes are recorded in
 * [written] and passed to [onRequest], which may answer through [send].
 */
class FakeTransport(
    sendReady: Boolean = true,
    var onRequest: (FakeTransport, JsonObject) -> Unit = { _, _ -> },
) : LineTransport {
    private val toClient = LinkedBlockingQueue<String>()
    val written = CopyOnWriteArrayList<JsonObject>()
    @Volatile var closed = false
        private set
    @Volatile var failWrites = false

    init {
        if (sendReady) send("""{"event":"ready","version":"1"}""")
    }

    fun send(line: String) {
        toClient.put(line)
    }

    fun reply(id: Long, resultJson: String) = send("""{"id":$id,"result":$resultJson}""")

    /** The server process dies: the client reads EOF. */
    fun die() {
        toClient.put(EOF)
    }

    /** Requests (not cancels) written so far. */
    fun requests(): List<JsonObject> = written.filter { it.has("id") }

    fun cancels(): List<Long> = written.filter { it.get("method")?.asString == "cancel" }
        .map { it.getAsJsonObject("params").get("id").asLong }

    override fun writeLine(line: String) {
        if (closed || failWrites) throw IOException("broken pipe")
        val obj = JsonParser.parseString(line).asJsonObject
        written += obj
        if (obj.has("id")) onRequest(this, obj) // cancels have no id and get no answer
    }

    override fun readLine(): String? {
        while (true) {
            if (closed) return null
            val line = toClient.poll(20, TimeUnit.MILLISECONDS) ?: continue
            return if (line === EOF) null.also { closed = true } else line
        }
    }

    override val isAlive: Boolean get() = !closed

    override fun close() {
        closed = true
    }

    private companion object {
        val EOF = String(charArrayOf('E', 'O', 'F'))
    }
}

/** Answers every request with [results] keyed by method (raw JSON). */
fun autoReply(vararg results: Pair<String, String>): (FakeTransport, JsonObject) -> Unit {
    val map = results.toMap()
    return { t, req ->
        val id = req.get("id")?.asLong
        val result = map[req.get("method")?.asString]
        if (id != null && result != null) t.reply(id, result)
    }
}
