package dev.skyloft.skypython.console

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.PythonProcess
import dev.skyloft.skypython.runtime.RunCommand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ConsoleState(
    val lines: List<ConsoleLine> = emptyList(),
    /** Bumped on every output change, even when the line count stays the same. */
    val version: Long = 0,
    val running: Boolean = false,
    /** True only while the script process is alive; input typed otherwise would be lost. */
    val acceptsInput: Boolean = false,
    val installing: Boolean = false,
    val installError: String? = null,
    /** Script name, "Interpreter" or "Terminal"; null before anything ran. */
    val title: String? = null,
    /** A start requested while something was running; the UI asks before replacing it. */
    val pending: RunTarget? = null,
) {
    /** The pending script, if [pending] is a script run. */
    val pendingRun: File? get() = (pending as? RunTarget.Script)?.file
}

/** The part of [PythonProcess] the console needs; lets tests drive the view model. */
interface ScriptProcess {
    val isRunning: Boolean
    fun start(command: RunCommand): Flow<ConsoleEvent>
    fun write(text: String)
    fun stop()
}

private class PythonScriptProcess(private val p: PythonProcess) : ScriptProcess {
    override val isRunning get() = p.isRunning
    override fun start(command: RunCommand) = p.start(command)
    override fun write(text: String) = p.write(text)
    override fun stop() = p.stop()
}

/**
 * Owns the running script, interpreter or terminal (one at a time) and the console buffer. All buffer mutation happens on the
 * single-threaded [work] dispatcher; only immutable snapshots go to [state] (read on Main),
 * at most once per [tickMillis].
 */
class ConsoleViewModel(
    /** Working directory of the interpreter and the terminal. */
    private val projectsRoot: File,
    private val install: suspend () -> Result<PythonPaths>,
    private val newProcess: (PythonPaths) -> ScriptProcess,
    private val work: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val tickMillis: Long = 50,
) : ViewModel() {

    private val buffer = ConsoleBuffer() // confined to [work]
    private val _state = MutableStateFlow(ConsoleState())
    val state: StateFlow<ConsoleState> = _state.asStateFlow()

    private var paths: PythonPaths? = null
    @Volatile private var process: ScriptProcess? = null
    private var runJob: Job? = null
    private var lastTarget: RunTarget? = null

    fun run(script: File) = start(RunTarget.Script(script))

    /** Starts [target], stopping whatever runs now without asking (see [request]). */
    fun start(target: RunTarget) {
        lastTarget = target
        val previous = runJob
        _state.update { it.copy(running = true, title = target.title) }
        runJob = viewModelScope.launch(work) {
            previous?.let { process?.stop(); it.cancelAndJoin() }
            buffer.clear()
            publish(running = true, title = target.title, acceptsInput = false)

            val p = paths ?: installPython() ?: return@launch
            val proc = newProcess(p).also { process = it }

            val ticker = launch {
                while (true) {
                    delay(tickMillis)
                    publishIfChanged(acceptsInput = proc.isRunning)
                }
            }
            try {
                proc.start(target.toCommand()).collect { event ->
                    when (event) {
                        is ConsoleEvent.Stdout -> buffer.append(event.text, isError = false)
                        is ConsoleEvent.Stderr -> appendStderr(event.text, target)
                        is ConsoleEvent.Failed -> buffer.append(event.message + "\n", isError = true)
                        is ConsoleEvent.Exited -> buffer.append(
                            exitLine(event.code, afterNewline = buffer.endsWithNewline),
                            isError = false,
                        )
                    }
                }
            } finally {
                ticker.cancel()
                if (process === proc) process = null
            }
            publish(running = false, acceptsInput = false)
        }
    }

    /**
     * Only one thing runs at a time: if something is running, [target] becomes
     * [ConsoleState.pending] and nothing starts until [confirmPendingRun].
     * @return true if it started now.
     */
    fun request(target: RunTarget): Boolean {
        if (_state.value.running) {
            _state.update { it.copy(pending = target) }
            return false
        }
        start(target)
        return true
    }

    /** [request] for a script. */
    fun requestRun(script: File): Boolean = request(RunTarget.Script(script))

    /** Python REPL in the projects root; [request] rules. */
    fun startInterpreter(): Boolean = request(RunTarget.Interpreter)

    /** Shell in the projects root; [request] rules. */
    fun startTerminal(): Boolean = request(RunTarget.Terminal)

    /** Stops what is running and starts the pending target. */
    fun confirmPendingRun() {
        val target = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        start(target)
    }

    fun dismissPendingRun() {
        _state.update { it.copy(pending = null) }
    }

    fun send(line: String) {
        val proc = process ?: return
        viewModelScope.launch(work) {
            if (!proc.isRunning) return@launch
            buffer.append(line + "\n", isError = false)
            proc.write(line + "\n")
            publish()
        }
    }

    /**
     * Stops the script. Before the process is up (e.g. while Python is being unpacked on
     * first run) there is nothing to kill, so the run itself is cancelled instead.
     */
    fun stop() {
        val proc = process
        if (proc != null && proc.isRunning) {
            proc.stop()
            return
        }
        val job = runJob ?: return
        if (!job.isActive) return
        job.cancel() // cancelling the collector also kills a process that is just starting
        viewModelScope.launch(work) {
            buffer.append(if (buffer.endsWithNewline) CANCELLED else "\n" + CANCELLED, false)
            _state.update { it.copy(installing = false) }
            publish(running = false, acceptsInput = false)
        }
    }

    /** Re-runs the last script, interpreter or terminal. */
    fun restart() {
        lastTarget?.let(::start)
    }

    fun retryInstall() {
        _state.update { it.copy(installError = null) }
        restart()
    }

    private fun RunTarget.toCommand(): RunCommand = when (this) {
        is RunTarget.Script -> RunCommand.Script(file)
        RunTarget.Interpreter -> RunCommand.Interpreter(projectsRoot)
        RunTarget.Terminal -> RunCommand.Shell(projectsRoot)
    }

    /** Call on [work] only. A trailing REPL/shell prompt is shown as normal text, not red. */
    private fun appendStderr(text: String, target: RunTarget) {
        val prompt = promptLength(text, target)
        buffer.append(text.substring(0, text.length - prompt), isError = true)
        buffer.append(text.substring(text.length - prompt), isError = false)
    }

    private suspend fun installPython(): PythonPaths? {
        _state.update { it.copy(installing = true, installError = null) }
        val result = install()
        _state.update { it.copy(installing = false) }
        return result.fold(
            onSuccess = { it.also { paths = it } },
            onFailure = { e ->
                _state.update {
                    it.copy(running = false, installError = e.message ?: e.toString())
                }
                null
            },
        )
    }

    private fun publishIfChanged(acceptsInput: Boolean) {
        val s = _state.value
        if (buffer.version != s.version || acceptsInput != s.acceptsInput) {
            publish(acceptsInput = acceptsInput)
        }
    }

    /** Call on [work] only. */
    private fun publish(
        running: Boolean? = null,
        title: String? = null,
        acceptsInput: Boolean? = null,
    ) {
        val lines = buffer.lines
        val version = buffer.version
        _state.update {
            it.copy(
                lines = lines,
                version = version,
                running = running ?: it.running,
                acceptsInput = acceptsInput ?: it.acceptsInput,
                title = title ?: it.title,
            )
        }
    }

    override fun onCleared() {
        process?.stop()
    }

    companion object {
        private const val CANCELLED = "[Cancelled]\n"

        /** The footer; starts with '\n' only if output didn't already end with one. */
        fun exitLine(code: Int, afterNewline: Boolean): String {
            val footer = "[Program finished, exit code $code]\n"
            return if (afterNewline) footer else "\n" + footer
        }

        /** @param projectsRoot working directory of the interpreter and the terminal. */
        fun factory(app: Application, projectsRoot: File): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val installer = PythonInstaller(app)
                ConsoleViewModel(
                    projectsRoot = projectsRoot,
                    install = installer::ensureInstalled,
                    newProcess = { PythonScriptProcess(PythonProcess(it)) },
                )
            }
        }
    }
}
