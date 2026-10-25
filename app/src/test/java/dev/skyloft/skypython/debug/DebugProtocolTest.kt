package dev.skyloft.skypython.debug

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugProtocolTest {

    @Test fun `request line carries id method and params`() {
        val line = DebugProtocol.request(7, "evaluate", DebugProtocol.evaluateParams(1, "x + 1"))
        val obj = JsonParser.parseString(line).asJsonObject
        assertEquals(7, obj["id"].asInt)
        assertEquals("evaluate", obj["method"].asString)
        assertEquals(1, obj["params"].asJsonObject["frameId"].asInt)
        assertEquals("x + 1", obj["params"].asJsonObject["expression"].asString)
    }

    @Test fun `breakpoint params keep conditions and omit blank ones`() {
        val params = DebugProtocol.breakpointsParams("/p/main.py", listOf(Breakpoint(3, "i > 2"), Breakpoint(5)))
        assertEquals("/p/main.py", params["file"].asString)
        val list = params["breakpoints"].asJsonArray
        assertEquals(3, list[0].asJsonObject["line"].asInt)
        assertEquals("i > 2", list[0].asJsonObject["condition"].asString)
        assertNull(list[1].asJsonObject["condition"])
    }

    @Test fun `stopped event with frames and exception`() {
        val msg = DebugProtocol.parse(
            """{"event":"stopped","reason":"exception","exception":"ZeroDivisionError: division by zero",""" +
                """"frames":[{"id":0,"name":"f","file":"/p/a.py","line":2,"hasLocals":true,"user":true},""" +
                """{"id":1,"name":"<module>","file":"/p/a.py","line":5,"hasLocals":false,"user":true}]}""",
        ) as DebugProtocol.Incoming.Event
        val e = msg.event as DebugEvent.Stopped
        assertEquals(StopReason.EXCEPTION, e.reason)
        assertEquals("ZeroDivisionError: division by zero", e.exception)
        assertEquals(StackFrame(0, "f", "/p/a.py", 2, hasLocals = true, user = true), e.frames[0])
        assertEquals("<module>", e.frames[1].name)
    }

    @Test fun `other events`() {
        assertEquals(DebugEvent.Continued, (DebugProtocol.parse("""{"event":"continued"}""") as DebugProtocol.Incoming.Event).event)
        assertEquals(DebugEvent.Exited(3), (DebugProtocol.parse("""{"event":"exited","code":3}""") as DebugProtocol.Incoming.Event).event)
        assertEquals(DebugEvent.Ready(42), (DebugProtocol.parse("""{"event":"ready","pid":42,"python":"3.14.9","protocol":"1"}""") as DebugProtocol.Incoming.Event).event)
        assertNull(DebugProtocol.parse("""{"event":"mystery"}"""))
        assertNull(DebugProtocol.parse("not json"))
    }

    @Test fun `responses with results and errors`() {
        val ok = DebugProtocol.parse("""{"id":4,"result":true}""") as DebugProtocol.Incoming.Response
        assertEquals(4L, ok.id)
        assertNull(ok.error)
        val failed = DebugProtocol.parse("""{"id":5,"error":"not stopped"}""") as DebugProtocol.Incoming.Response
        assertEquals("not stopped", failed.error)
    }

    @Test fun `variables page`() {
        val page = DebugProtocol.variablesPage(
            JsonParser.parseString(
                """{"variables":[{"name":"x","value":"1","type":"int","ref":0},""" +
                    """{"name":"items","value":"[1, 2]","type":"list","ref":3,"size":2}],"more":5}""",
            ),
        )
        assertEquals(listOf(Variable("x", "1", "int", 0, null), Variable("items", "[1, 2]", "list", 3, 2)), page.variables)
        assertEquals(5, page.more)
    }

    @Test fun `evaluate results`() {
        assertEquals(
            EvalResult.Value(Variable("", "12", "int", 0, null)),
            DebugProtocol.evalResult(JsonParser.parseString("""{"value":"12","type":"int","ref":0}""")),
        )
        assertEquals(
            EvalResult.Error("NameError: name 'm' is not defined"),
            DebugProtocol.evalResult(JsonParser.parseString("""{"error":"NameError: name 'm' is not defined"}""")),
        )
    }

    @Test fun `breakpoint results`() {
        val results = DebugProtocol.breakpointResults(
            JsonParser.parseString("""[{"line":2,"actualLine":4,"verified":true},{"line":40,"actualLine":40,"verified":false,"message":"no code"}]"""),
        )
        assertEquals(BreakpointResult(2, 4, true, null), results[0])
        assertEquals(BreakpointResult(40, 40, false, "no code"), results[1])
    }
}
