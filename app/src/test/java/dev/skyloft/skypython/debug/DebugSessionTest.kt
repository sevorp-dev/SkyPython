package dev.skyloft.skypython.debug

import com.google.gson.JsonObject
import dev.skyloft.skypython.lang.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSessionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private val frames = """[{"id":0,"name":"area","file":"/p/main.py","line":2,"hasLocals":true,"user":true},""" +
        """{"id":1,"name":"<module>","file":"/p/main.py","line":5,"hasLocals":false,"user":true}]"""

    /** A driver that answers like skydbg for a program stopped in `area(w=3, h=4)`. */
    private fun driver() = FakeTransport(sendReady = false) { t, req ->
        val id = req["id"].asLong
        val params = req["params"]?.asJsonObject ?: JsonObject()
        when (req["method"].asString) {
            "setBreakpoints" -> t.reply(id, """[{"line":2,"actualLine":2,"verified":true}]""")
            "variables" -> when {
                params.has("ref") -> t.reply(id, """{"variables":[{"name":"[0]","value":"1","type":"int","ref":0}],"more":0}""")
                params["scope"].asString == "locals" && params["frameId"].asInt == 0 ->
                    t.reply(id, """{"variables":[{"name":"w","value":"3","type":"int","ref":0},{"name":"h","value":"[1]","type":"list","ref":7,"size":1}],"more":0}""")
                params["scope"].asString == "locals" -> t.send("""{"id":$id,"error":"no locals"}""")
                else -> t.reply(id, """{"variables":[{"name":"total","value":"0","type":"int","ref":0}],"more":0}""")
            }
            "evaluate" -> when (params["expression"].asString) {
                "w * 2" -> t.reply(id, """{"value":"${if (params["frameId"].asInt == 0) "6" else "?"}","type":"int","ref":0}""")
                else -> t.reply(id, """{"error":"NameError: name 'zz' is not defined"}""")
            }
            else -> t.reply(id, "true")
        }
    }

    private fun session() = DebugSession(scope)

    private suspend fun DebugSession.await(what: String, predicate: (DebugState) -> Boolean): DebugState =
        try {
            withTimeout(5_000) { state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what; state: ${state.value}", e)
        }

    private fun FakeTransport.methods() = requests().map { it["method"].asString }

    @Test fun `configures breakpoints, then starts with the user roots`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        assertEquals(DebugPhase.Starting, s.state.value.phase)
        s.attach(
            t,
            breakpoints = mapOf("/p/main.py" to listOf(Breakpoint(2)), "/p/util.py" to listOf(Breakpoint(9, "x"))),
            watches = emptyList(),
            userRoots = listOf("/p"),
        )
        s.await("running") { it.phase == DebugPhase.Running }
        withTimeout(5_000) { while (t.methods().size < 3) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("setBreakpoints", "setBreakpoints", "start"), t.methods())
        val start = t.requests().last()["params"].asJsonObject
        assertEquals("/p", start["userRoots"].asJsonArray[0].asString)
        val util = t.requests()[1]["params"].asJsonObject
        assertEquals("x", util["breakpoints"].asJsonArray[0].asJsonObject["condition"].asString)
    }

    @Test fun `stopping loads frames, variables and watches of the innermost user frame`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), watches = listOf("w * 2", "zz"), userRoots = emptyList())
        t.send("""{"event":"stopped","reason":"breakpoint","frames":$frames}""")
        val st = s.await("variables") { it.phase == DebugPhase.Paused && it.locals != null && it.globals != null && it.watches.all { w -> w.result != null } }
        assertEquals(StopReason.BREAKPOINT, st.reason)
        assertEquals(0, st.selectedFrame)
        assertEquals(listOf("w", "h"), st.locals!!.variables.map { it.name })
        assertEquals(listOf("total"), st.globals!!.variables.map { it.name })
        assertEquals(EvalResult.Value(Variable("", "6", "int", 0, null)), st.watches[0].result)
        assertEquals(EvalResult.Error("NameError: name 'zz' is not defined"), st.watches[1].result)
        assertEquals(st.frames[0], st.currentFrame)
    }

    @Test fun `module frame has no locals scope`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        t.send("""{"event":"stopped","reason":"step","frames":[{"id":0,"name":"<module>","file":"/p/main.py","line":5,"hasLocals":false,"user":true}]}""")
        val st = s.await("globals") { it.globals != null }
        assertNull(st.locals)
        assertTrue(t.requests().none { it["method"].asString == "variables" && it["params"].asJsonObject["scope"]?.asString == "locals" })
    }

    @Test fun `the first user frame is selected when library code is on top`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        t.send(
            """{"event":"stopped","reason":"exception","exception":"ValueError: x","frames":[""" +
                """{"id":0,"name":"loads","file":"/lib/json.py","line":9,"hasLocals":true,"user":false},""" +
                """{"id":1,"name":"<module>","file":"/p/main.py","line":5,"hasLocals":false,"user":true}]}""",
        )
        val st = s.await("stop") { it.phase == DebugPhase.Paused }
        assertEquals(1, st.selectedFrame)
        assertEquals("ValueError: x", st.exception)
    }

    @Test fun `selecting a frame reloads its variables and watches`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), watches = listOf("w * 2"), userRoots = emptyList())
        t.send("""{"event":"stopped","reason":"breakpoint","frames":$frames}""")
        s.await("loaded") { it.locals != null }
        s.selectFrame(1)
        val st = s.await("frame 1") { it.selectedFrame == 1 && it.locals == null && it.globals != null && it.watches[0].result != null }
        assertEquals(EvalResult.Value(Variable("", "?", "int", 0, null)), st.watches[0].result)
        assertEquals(5, st.currentFrame!!.line)
    }

    @Test fun `expanding a container fetches its children once`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        t.send("""{"event":"stopped","reason":"breakpoint","frames":$frames}""")
        s.await("loaded") { it.locals != null }
        s.toggleExpanded(7)
        val st = s.await("children") { 7 in it.children }
        assertEquals(setOf(7), st.expanded)
        assertEquals("[0]", st.children.getValue(7).variables.single().name)
        s.toggleExpanded(7)
        s.await("collapsed") { it.expanded.isEmpty() }
        s.toggleExpanded(7)
        s.await("expanded again") { 7 in it.expanded }
        assertEquals(1, t.requests().count { it["params"].asJsonObject.has("ref") })
    }

    @Test fun `stepping sends the command and running clears the stop`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        t.send("""{"event":"stopped","reason":"breakpoint","frames":$frames}""")
        s.await("paused") { it.phase == DebugPhase.Paused }
        s.resume(Step.Over)
        withTimeout(5_000) { while ("next" !in t.methods()) kotlinx.coroutines.delay(10) }
        t.send("""{"event":"continued"}""")
        val st = s.await("running") { it.phase == DebugPhase.Running }
        assertTrue(st.frames.isEmpty())
        assertNull(st.locals)
        assertTrue(st.children.isEmpty())
        for ((step, method) in listOf(Step.Into to "stepIn", Step.Out to "stepOut", Step.Continue to "continue")) {
            t.send("""{"event":"stopped","reason":"step","frames":$frames}""")
            s.await("paused") { it.phase == DebugPhase.Paused }
            s.resume(step)
            withTimeout(5_000) { while (method !in t.methods()) kotlinx.coroutines.delay(10) }
            t.send("""{"event":"continued"}""")
            s.await("running") { it.phase == DebugPhase.Running }
        }
    }

    @Test fun `pause and breakpoint updates go to the driver while running`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        s.await("running") { it.phase == DebugPhase.Running }
        s.pause()
        s.updateBreakpoints("/p/main.py", listOf(Breakpoint(4)))
        withTimeout(5_000) { while (t.methods().count { it == "pause" || it == "setBreakpoints" } < 2) kotlinx.coroutines.delay(10) }
        val bp = t.requests().last { it["method"].asString == "setBreakpoints" }["params"].asJsonObject
        assertEquals(4, bp["breakpoints"].asJsonArray[0].asJsonObject["line"].asInt)
    }

    @Test fun `exit code is kept when the program ends`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        t.send("""{"event":"exited","code":3}""")
        t.die()
        val st = s.await("ended") { it.phase == DebugPhase.Ended }
        assertEquals(3, st.exitCode)
        assertNull(st.error)
    }

    @Test fun `a lost connection while stopped ends the session`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        t.send("""{"event":"stopped","reason":"breakpoint","frames":$frames}""")
        s.await("paused") { it.phase == DebugPhase.Paused }
        t.die()
        val st = s.await("ended") { it.phase == DebugPhase.Ended }
        assertTrue(st.frames.isEmpty())
        assertNull(st.exitCode)
    }

    @Test fun `failing to connect ends with an error and closing returns to idle`() = runBlocking {
        val s = session()
        s.begin("main.py")
        s.fail("The debugger did not start")
        assertEquals(DebugPhase.Ended, s.state.value.phase)
        assertEquals("The debugger did not start", s.state.value.error)
        s.close()
        assertEquals(DebugState(), s.state.value)
    }

    @Test fun `watches can change while paused`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        t.send("""{"event":"stopped","reason":"breakpoint","frames":$frames}""")
        s.await("paused") { it.locals != null }
        s.setWatches(listOf("w * 2"))
        val st = s.await("watch") { it.watches.singleOrNull()?.result != null }
        assertEquals("6", (st.watches.single().result as EvalResult.Value).variable.value)
    }

    @Test fun `stop asks the driver to end the program`() = runBlocking {
        val t = driver()
        val s = session()
        s.begin("main.py")
        s.attach(t, emptyMap(), emptyList(), emptyList())
        s.await("running") { it.phase == DebugPhase.Running }
        s.stop()
        withTimeout(5_000) { while ("stop" !in t.methods()) kotlinx.coroutines.delay(10) }
    }
}
