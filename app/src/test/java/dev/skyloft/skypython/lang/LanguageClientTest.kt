package dev.skyloft.skypython.lang

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun client(t: FakeTransport) = LanguageClient(t, scope)

    private fun params(source: String) = JsonObject().apply { addProperty("source", source) }

    @Test
    fun readyEventMarksTheClientReady() = runBlocking {
        val t = FakeTransport(sendReady = false)
        val c = client(t)
        assertFalse(c.isReady)
        t.send("""{"event":"ready","version":"1"}""")
        assertTrue(c.awaitReady(2_000))
        assertTrue(c.isReady)
    }

    @Test
    fun requestsGetIncreasingIdsAndResponsesAreMatchedById() = runBlocking {
        val t = FakeTransport()
        val c = client(t)
        val a = async { c.call("ping", JsonObject(), 2_000) }
        val b = async { c.call("ping", JsonObject(), 2_000) }
        withTimeout(2_000) { while (t.requests().size < 2) delay(5) }
        val ids = t.requests().map { it.get("id").asLong }.sorted()
        assertEquals(ids[0] + 1, ids[1])
        // Answer out of order.
        t.reply(ids[1], "\"second\"")
        t.reply(ids[0], "\"first\"")
        val ra = a.await() as Reply.Ok
        val rb = b.await() as Reply.Ok
        assertEquals(setOf("first", "second"), setOf(ra.result!!.asString, rb.result!!.asString))
        val firstId = t.requests().first { it.get("id").asLong == ids[0] }
        assertEquals("ping", firstId.get("method").asString)
    }

    @Test
    fun requestCarriesMethodAndParams() = runBlocking {
        val t = FakeTransport(onRequest = autoReply("diagnose" to "[]"))
        client(t).call("diagnose", params("x = 1\n"), 2_000)
        val req = t.requests().single()
        assertEquals("diagnose", req.get("method").asString)
        assertEquals("x = 1\n", req.getAsJsonObject("params").get("source").asString)
    }

    @Test
    fun errorAndCancelledRepliesAreReported() = runBlocking {
        val t = FakeTransport(onRequest = { tr, req ->
            val id = req.get("id").asLong
            if (req.get("method").asString == "bad") tr.send("""{"id":$id,"error":"boom"}""")
            else tr.send("""{"id":$id,"cancelled":true}""")
        })
        val c = client(t)
        assertEquals(Reply.Failed("boom"), c.call("bad", JsonObject(), 2_000))
        assertEquals(Reply.Cancelled, c.call("stale", JsonObject(), 2_000))
    }

    @Test
    fun timeoutSendsCancelForThatRequest() = runBlocking {
        val t = FakeTransport() // never answers
        val c = client(t)
        assertEquals(Reply.TimedOut, c.call("complete", params("x"), 50))
        val id = t.requests().single().get("id").asLong
        withTimeout(2_000) { while (t.cancels().isEmpty()) delay(5) }
        assertEquals(listOf(id), t.cancels())
        assertEquals(1, c.consecutiveTimeouts)
    }

    @Test
    fun successResetsTheTimeoutCount() = runBlocking {
        var answer = false
        val t = FakeTransport(onRequest = { tr, req -> if (answer) tr.reply(req.get("id").asLong, "1") })
        val c = client(t)
        c.call("a", JsonObject(), 30)
        c.call("a", JsonObject(), 30)
        assertEquals(2, c.consecutiveTimeouts)
        answer = true
        c.call("a", JsonObject(), 2_000)
        assertEquals(0, c.consecutiveTimeouts)
    }

    @Test
    fun cancellingTheCallerSendsCancel() = runBlocking {
        val t = FakeTransport()
        val c = client(t)
        val job = launch(Dispatchers.IO) { c.call("complete", params("x"), 10_000) }
        withTimeout(2_000) { while (t.requests().isEmpty()) delay(5) }
        job.cancelAndJoin()
        withTimeout(2_000) { while (t.cancels().isEmpty()) delay(5) }
        assertEquals(t.requests().single().get("id").asLong, t.cancels().single())
    }

    @Test
    fun lateReplyToACancelledRequestIsIgnored() = runBlocking {
        val t = FakeTransport()
        val c = client(t)
        assertEquals(Reply.TimedOut, c.call("complete", params("x"), 30))
        val stale = t.requests().single().get("id").asLong
        t.onRequest = autoReply("ping" to "\"pong\"")
        t.reply(stale, "\"late\"")
        assertEquals("pong", (c.call("ping", JsonObject(), 2_000) as Reply.Ok).result!!.asString)
    }

    @Test
    fun serverDeathEndsPendingCallsAndLaterCallsFailFast() = runBlocking {
        val t = FakeTransport()
        val c = client(t)
        val pending = async { c.call("complete", params("x"), 10_000) }
        withTimeout(2_000) { while (t.requests().isEmpty()) delay(5) }
        t.die()
        assertEquals(Reply.Gone, withTimeout(2_000) { pending.await() })
        assertTrue(c.isClosed)
        assertEquals(Reply.Gone, c.call("ping", JsonObject(), 2_000))
    }

    @Test
    fun brokenPipeOnWriteIsGone() = runBlocking {
        val t = FakeTransport()
        t.failWrites = true
        val c = client(t)
        assertEquals(Reply.Gone, c.call("ping", JsonObject(), 2_000))
        assertTrue(c.isClosed)
    }

    @Test
    fun garbageLinesAreSkipped() = runBlocking {
        val t = FakeTransport(onRequest = { tr, req ->
            tr.send("Traceback (most recent call last):")
            tr.send("[1, 2]")
            tr.send("""{"id":"nope"}""")
            tr.reply(req.get("id").asLong, "\"ok\"")
        })
        assertEquals("ok", (client(t).call("ping", JsonObject(), 2_000) as Reply.Ok).result!!.asString)
    }

    @Test
    fun completeParsesItems() = runBlocking {
        val t = FakeTransport(onRequest = autoReply(
            "complete" to """[{"name":"json","complete":"on","type":"module"},{"name":"jsonx"}]""",
        ))
        val items = client(t).complete("import js", line = 1, column = 9, path = "/p/a.py", timeoutMs = 2_000)
        assertEquals(listOf(Completion("json", "on", "module"), Completion("jsonx", "", "")), items)
        val p = t.requests().single().getAsJsonObject("params")
        assertEquals(1, p.get("line").asInt)
        assertEquals(9, p.get("column").asInt)
        assertEquals("/p/a.py", p.get("path").asString)
    }

    @Test
    fun navigationRequestsSendThePositionAndParseTheAnswer() = runBlocking {
        val t = FakeTransport(onRequest = autoReply(
            "definition" to """[{"name":"f","type":"function","path":"/p/h.py","kind":"project","line":2,"column":4,"endColumn":5,"inFile":false}]""",
            "references" to """[{"line":1,"column":0,"endColumn":1}]""",
            "signature" to """{"name":"print","params":["*values: object"],"short":["*values"],"index":0}""",
            "outline" to """[{"name":"f","kind":"function","line":2,"column":4,"endLine":3,"children":[]}]""",
        ))
        val c = client(t)
        assertEquals("/p/h.py", c.definition("f()", 1, 1, "/p/a.py", 2_000)!!.single().path)
        assertEquals(listOf(Usage(1, 0, 1)), c.references("f", 1, 0, null, 2_000))
        assertEquals(listOf("*values"), c.signature("print(", 1, 6, null, 2_000)!!.short)
        assertEquals("f", c.outline("def f(): pass", 2_000)!!.single().name)
        val def = t.requests().first { it.get("method").asString == "definition" }.getAsJsonObject("params")
        assertEquals(1, def.get("line").asInt)
        assertEquals(1, def.get("column").asInt)
        assertEquals("/p/a.py", def.get("path").asString)
    }

    @Test
    fun signatureOutsideACallIsNull() = runBlocking {
        val t = FakeTransport(onRequest = autoReply("signature" to "null"))
        assertNull(client(t).signature("x = 1", 1, 5, null, 2_000))
    }

    @Test
    fun diagnoseParsesDiagnostics() = runBlocking {
        val t = FakeTransport(onRequest = autoReply(
            "diagnose" to """[{"line":1,"column":4,"endLine":1,"endColumn":5,"severity":"error","message":"SyntaxError: '(' was never closed"},
                {"line":3,"column":0,"endLine":3,"endColumn":2,"severity":"warning","message":"'os' imported but unused"}]""",
        ))
        val diags = client(t).diagnose("x = (\n", path = null, timeoutMs = 2_000)
        assertEquals(
            listOf(
                Diagnostic(1, 4, 1, 5, Severity.ERROR, "SyntaxError: '(' was never closed"),
                Diagnostic(3, 0, 3, 2, Severity.WARNING, "'os' imported but unused"),
            ),
            diags,
        )
        assertFalse(t.requests().single().getAsJsonObject("params").has("path"))
    }

    @Test
    fun failedTypedCallsReturnNull() = runBlocking {
        val t = FakeTransport(onRequest = { tr, req -> tr.send("""{"id":${req.get("id").asLong},"error":"x"}""") })
        val c = client(t)
        assertNull(c.complete("x", 1, 1, null, 2_000))
        assertNull(c.diagnose("x", null, 2_000))
    }

    @Test
    fun malformedResultsReturnNullInsteadOfCrashing() = runBlocking {
        val t = FakeTransport(onRequest = autoReply("complete" to "{\"not\":\"a list\"}", "diagnose" to "[{\"line\":\"x\"}]"))
        val c = client(t)
        assertNull(c.complete("x", 1, 1, null, 2_000))
        assertNull(c.diagnose("x", null, 2_000))
    }
}
