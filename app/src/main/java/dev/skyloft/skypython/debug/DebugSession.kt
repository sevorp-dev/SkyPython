package dev.skyloft.skypython.debug

import dev.skyloft.skypython.runtime.LineTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

enum class DebugPhase {
    /** No debug session: the panel is hidden. */
    Idle,
    /** Python is starting (or being installed) and the driver not connected yet. */
    Starting,
    Running,
    /** Stopped at a breakpoint, after a step, a pause, or on an uncaught exception. */
    Paused,
    /** The program ended (or the debugger couldn't start); the panel stays until closed. */
    Ended,
}

enum class Step { Continue, Over, Into, Out }

data class WatchValue(val expression: String, val result: EvalResult? = null)

data class DebugState(
    val phase: DebugPhase = DebugPhase.Idle,
    /** Name of the script being debugged. */
    val title: String? = null,
    val reason: StopReason? = null,
    /** The uncaught exception, for [StopReason.EXCEPTION]. */
    val exception: String? = null,
    val frames: List<StackFrame> = emptyList(),
    val selectedFrame: Int = 0,
    /** Locals of the selected frame; null while loading or for module-level code. */
    val locals: VariablesPage? = null,
    val globals: VariablesPage? = null,
    /** Children of expanded containers, by reference. */
    val children: Map<Int, VariablesPage> = emptyMap(),
    val expanded: Set<Int> = emptySet(),
    val watches: List<WatchValue> = emptyList(),
    val exitCode: Int? = null,
    /** Why the session couldn't start. */
    val error: String? = null,
) {
    val currentFrame: StackFrame? get() = frames.getOrNull(selectedFrame)
    val isLive: Boolean get() = phase == DebugPhase.Starting || phase == DebugPhase.Running || phase == DebugPhase.Paused
}

/**
 * One debug session's state machine: Idle → [begin] Starting → [attach] Running ⇄ Paused →
 * Ended → [close] Idle. Everything the panel shows is in [state]; stops load the selected
 * frame's variables and the watches. Results that arrive after the program moved on are dropped.
 */
class DebugSession(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(DebugState())
    val state: StateFlow<DebugState> = _state.asStateFlow()

    @Volatile private var client: DebugClient? = null
    private var eventsJob: Job? = null
    private var loadJob: Job? = null
    /** Bumped on every stop, resume and frame change: a load for an older one is stale. */
    private val gen = AtomicLong()
    private val loadLock = Mutex()

    /** Shows the panel for [title] while Python starts. */
    fun begin(title: String) {
        detach()
        val watches = _state.value.watches.map { WatchValue(it.expression) }
        _state.value = DebugState(phase = DebugPhase.Starting, title = title, watches = watches)
    }

    /** The driver couldn't be reached. */
    fun fail(message: String) {
        detach()
        _state.update { it.copy(phase = DebugPhase.Ended, error = message) }
    }

    /**
     * Connected: sends [breakpoints] (canonical path → list), then starts the program with
     * stepping limited to [userRoots]. Events are handled until the connection closes.
     */
    fun attach(
        transport: LineTransport,
        breakpoints: Map<String, List<Breakpoint>>,
        watches: List<String>,
        userRoots: List<String>,
    ) {
        detach()
        val c = DebugClient(transport, scope, io)
        client = c
        _state.update { it.copy(watches = watches.map { e -> WatchValue(e) }) }
        eventsJob = scope.launch {
            launch {
                for ((file, list) in breakpoints) {
                    if (list.isNotEmpty()) c.call("setBreakpoints", DebugProtocol.breakpointsParams(file, list))
                }
                if (c.call("start", DebugProtocol.startParams(userRoots)) is DebugReply.Ok) {
                    _state.update { if (it.phase == DebugPhase.Starting) it.copy(phase = DebugPhase.Running) else it }
                }
            }
            c.events.collect { onEvent(c, it) }
        }
    }

    private fun onEvent(c: DebugClient, event: DebugEvent) {
        if (c !== client) return
        when (event) {
            is DebugEvent.Ready -> Unit
            is DebugEvent.Stopped -> {
                val selected = event.frames.indexOfFirst { it.user }.coerceAtLeast(0)
                gen.incrementAndGet()
                _state.update {
                    it.copy(
                        phase = DebugPhase.Paused,
                        reason = event.reason,
                        exception = event.exception,
                        frames = event.frames,
                        selectedFrame = selected,
                        locals = null,
                        globals = null,
                        children = emptyMap(),
                        expanded = emptySet(),
                        watches = it.watches.map { w -> WatchValue(w.expression) },
                    )
                }
                loadFrame()
            }
            DebugEvent.Continued -> {
                gen.incrementAndGet()
                _state.update { it.running() }
            }
            is DebugEvent.Exited -> _state.update { it.copy(exitCode = event.code) }
            DebugEvent.Disconnected -> {
                gen.incrementAndGet()
                client = null
                _state.update { it.running().copy(phase = DebugPhase.Ended) }
            }
        }
    }

    private fun DebugState.running() = copy(
        phase = DebugPhase.Running,
        reason = null,
        exception = null,
        frames = emptyList(),
        selectedFrame = 0,
        locals = null,
        globals = null,
        children = emptyMap(),
        expanded = emptySet(),
        watches = watches.map { WatchValue(it.expression) },
    )

    /** Loads the selected frame's scopes and watches for the current stop. */
    private fun loadFrame() {
        val c = client ?: return
        val token = gen.get()
        val st = _state.value
        val frame = st.currentFrame ?: return
        val expressions = st.watches.map { it.expression }
        loadJob = scope.launch {
            loadLock.withLock {
                if (token != gen.get()) return@withLock
                val locals = if (frame.hasLocals) page(c.call("variables", DebugProtocol.scopeParams(frame.id, globals = false))) else null
                val globals = page(c.call("variables", DebugProtocol.scopeParams(frame.id, globals = true)))
                apply(token) { it.copy(locals = locals, globals = globals ?: VariablesPage(emptyList(), 0)) }
                val results = expressions.map { evaluate(c, frame.id, it) }
                apply(token) { s ->
                    s.copy(watches = s.watches.map { w ->
                        val i = expressions.indexOf(w.expression)
                        if (i >= 0) w.copy(result = results[i]) else w
                    })
                }
            }
        }
    }

    private suspend fun evaluate(c: DebugClient, frameId: Int, expression: String): EvalResult =
        when (val r = c.call("evaluate", DebugProtocol.evaluateParams(frameId, expression))) {
            is DebugReply.Ok -> DebugProtocol.evalResult(r.result)
            is DebugReply.Failed -> EvalResult.Error(r.message)
        }

    private fun page(reply: DebugReply): VariablesPage? =
        (reply as? DebugReply.Ok)?.let { DebugProtocol.variablesPage(it.result) }

    private fun apply(token: Long, change: (DebugState) -> DebugState) {
        _state.update { if (token == gen.get() && it.phase == DebugPhase.Paused) change(it) else it }
    }

    fun resume(step: Step) {
        val c = client ?: return
        if (_state.value.phase != DebugPhase.Paused) return
        val method = when (step) {
            Step.Continue -> "continue"
            Step.Over -> "next"
            Step.Into -> "stepIn"
            Step.Out -> "stepOut"
        }
        scope.launch { c.call(method) }
    }

    fun pause() {
        val c = client ?: return
        if (_state.value.phase != DebugPhase.Running) return
        scope.launch { c.call("pause") }
    }

    /** Ends the program at once (the driver exits; the caller also stops the process). */
    fun stop() {
        val c = client ?: return
        scope.launch { c.call("stop", timeoutMs = 1_000) }
    }

    fun selectFrame(index: Int) {
        val st = _state.value
        if (st.phase != DebugPhase.Paused || index !in st.frames.indices || index == st.selectedFrame) return
        gen.incrementAndGet()
        _state.update {
            it.copy(
                selectedFrame = index,
                locals = null,
                globals = null,
                children = emptyMap(),
                expanded = emptySet(),
                watches = it.watches.map { w -> WatchValue(w.expression) },
            )
        }
        loadFrame()
    }

    fun toggleExpanded(ref: Int) {
        val st = _state.value
        if (st.phase != DebugPhase.Paused || ref <= 0) return
        if (ref in st.expanded) {
            _state.update { it.copy(expanded = it.expanded - ref) }
            return
        }
        _state.update { it.copy(expanded = it.expanded + ref) }
        if (ref in st.children) return
        val c = client ?: return
        val token = gen.get()
        scope.launch {
            val page = page(c.call("variables", DebugProtocol.refParams(ref))) ?: VariablesPage(emptyList(), 0)
            apply(token) { it.copy(children = it.children + (ref to page)) }
        }
    }

    /** The watch list changed: re-evaluated at once when paused. */
    fun setWatches(expressions: List<String>) {
        val st = _state.value
        val known = st.watches.associateBy { it.expression }
        _state.update { s -> s.copy(watches = expressions.map { known[it] ?: WatchValue(it) }) }
        val c = client ?: return
        val frame = st.currentFrame ?: return
        if (st.phase != DebugPhase.Paused) return
        val token = gen.get()
        val missing = expressions.filter { known[it]?.result == null }
        if (missing.isEmpty()) return
        scope.launch {
            val results = missing.associateWith { evaluate(c, frame.id, it) }
            apply(token) { s -> s.copy(watches = s.watches.map { w -> results[w.expression]?.let { r -> w.copy(result = r) } ?: w }) }
        }
    }

    /** A file's breakpoints changed during the session. */
    fun updateBreakpoints(file: String, breakpoints: List<Breakpoint>) {
        val c = client ?: return
        scope.launch { c.call("setBreakpoints", DebugProtocol.breakpointsParams(file, breakpoints)) }
    }

    /** Hides the panel. */
    fun close() {
        detach()
        _state.value = DebugState()
    }

    private fun detach() {
        gen.incrementAndGet()
        eventsJob?.cancel()
        loadJob?.cancel()
        client?.close()
        client = null
    }
}
