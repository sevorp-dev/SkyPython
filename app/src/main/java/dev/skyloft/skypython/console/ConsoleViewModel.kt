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
    val scriptName: String? = null,
    /** A Run requested while a script was running; the UI asks before restarting. */
    val pendingRun: File? = null,
)

/** The part of [PythonProcess] the console needs; lets tests drive the view model. */
interface ScriptProcess {
    val isRunning: Boolean
    fun start(script: File): Flow<ConsoleEvent>
    fun write(text: String)
    fun stop()
}

private class PythonScriptProcess(private val p: PythonProcess) : ScriptProcess {
    override val isRunning get() = p.isRunning
    override fun start(script: File) = p.start(script)
    override fun write(text: String) = p.write(text)
    override fun stop() = p.stop()
}

/**
 * Owns the running script and the console buffer. All buffer mutation happens on the
 * single-threaded [work] dispatcher; only immutable snapshots go to [state] (read on Main),
 * at most once per [tickMillis].
 */
class ConsoleViewModel(
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
    private var lastScript: File? = null

    fun run(script: File) {
        lastScript = script
        val previous = runJob
        _state.update { it.copy(running = true, scriptName = script.name) }
        runJob = viewModelScope.launch(work) {
            previous?.let { process?.stop(); it.cancelAndJoin() }
            buffer.clear()
            publish(running = true, scriptName = script.name, acceptsInput = false)

            val p = paths ?: installPython() ?: return@launch
            val proc = newProcess(p).also { process = it }

            val ticker = launch {
                while (true) {
                    delay(tickMillis)
                    publishIfChanged(acceptsInput = proc.isRunning)
                }
            }
            try {
                proc.start(script).collect { event ->
                    when (event) {
                        is ConsoleEvent.Stdout -> buffer.append(event.text, isError = false)
                        is ConsoleEvent.Stderr -> buffer.append(event.text, isError = true)
                        is ConsoleEvent.Failed -> buffer.append(event.message + "\n", isError = true)
                        is ConsoleEvent.Exited -> buffer.append(exitLine(event.code), isError = false)
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
     * Only one script runs at a time: if one is running, [script] becomes
     * [ConsoleState.pendingRun] and nothing starts until [confirmPendingRun].
     * @return true if the run started now.
     */
    fun requestRun(script: File): Boolean {
        if (_state.value.running) {
            _state.update { it.copy(pendingRun = script) }
            return false
        }
        run(script)
        return true
    }

    /** Stops the running script and runs the pending one. */
    fun confirmPendingRun() {
        val script = _state.value.pendingRun ?: return
        _state.update { it.copy(pendingRun = null) }
        run(script)
    }

    fun dismissPendingRun() {
        _state.update { it.copy(pendingRun = null) }
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

    fun restart() {
        lastScript?.let(::run)
    }

    fun retryInstall() {
        _state.update { it.copy(installError = null) }
        restart()
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
        scriptName: String? = null,
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
                scriptName = scriptName ?: it.scriptName,
            )
        }
    }

    override fun onCleared() {
        process?.stop()
    }

    companion object {
        private const val CANCELLED = "[Cancelled]\n"

        fun exitLine(code: Int) = "\n[Program finished, exit code $code]\n"

        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val installer = PythonInstaller(app)
                ConsoleViewModel(
                    install = installer::ensureInstalled,
                    newProcess = { PythonScriptProcess(PythonProcess(it)) },
                )
            }
        }
    }
}
