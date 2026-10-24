package dev.skyloft.skypython.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.PythonProcess
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** One console line split into stdout / stderr pieces, ready to draw. */
data class ConsoleLine(val pieces: List<Segment>)

data class ConsoleState(
    val segments: List<Segment> = emptyList(),
    val lines: List<ConsoleLine> = emptyList(),
    val running: Boolean = false,
    val installing: Boolean = false,
    val installError: String? = null,
    val scriptName: String? = null,
)

class ConsoleViewModel(app: Application) : AndroidViewModel(app) {

    private val installer = PythonInstaller(app)
    private val buffer = ConsoleBuffer()
    private val _state = MutableStateFlow(ConsoleState())
    val state: StateFlow<ConsoleState> = _state.asStateFlow()

    private var paths: PythonPaths? = null
    private var process: PythonProcess? = null
    private var runJob: Job? = null
    private var lastScript: File? = null
    @Volatile private var dirty = false

    fun run(script: File) {
        lastScript = script
        val previous = runJob
        runJob = viewModelScope.launch {
            previous?.let { process?.stop(); it.join() }
            buffer.clear()
            publish(running = true, scriptName = script.name)

            val p = paths ?: installPython() ?: return@launch
            val proc = PythonProcess(p).also { process = it }

            val ticker = launch {
                while (true) {
                    delay(50)
                    if (dirty) publish()
                }
            }
            proc.start(script).collect { event ->
                when (event) {
                    is ConsoleEvent.Stdout -> buffer.append(event.text, isError = false)
                    is ConsoleEvent.Stderr -> buffer.append(event.text, isError = true)
                    is ConsoleEvent.Failed -> buffer.append(event.message + "\n", isError = true)
                    is ConsoleEvent.Exited -> buffer.append(exitLine(event.code), isError = false)
                }
                dirty = true
            }
            ticker.cancel()
            publish(running = false)
        }
    }

    fun send(line: String) {
        val proc = process ?: return
        if (!proc.isRunning) return
        buffer.append(line + "\n", isError = false)
        proc.write(line + "\n")
        publish()
    }

    fun stop() {
        process?.stop()
    }

    fun restart() {
        lastScript?.let(::run)
    }

    fun retryInstall() {
        _state.value = _state.value.copy(installError = null)
        restart()
    }

    private suspend fun installPython(): PythonPaths? {
        _state.value = _state.value.copy(installing = true, installError = null)
        val result = installer.ensureInstalled()
        _state.value = _state.value.copy(installing = false)
        return result.fold(
            onSuccess = { it.also { paths = it } },
            onFailure = {
                _state.value = _state.value.copy(
                    running = false,
                    installError = it.message ?: it.toString(),
                )
                null
            },
        )
    }

    private fun publish(running: Boolean? = null, scriptName: String? = null) {
        dirty = false
        val segments = buffer.segments
        _state.value = _state.value.copy(
            segments = segments,
            lines = toLines(segments),
            running = running ?: _state.value.running,
            scriptName = scriptName ?: _state.value.scriptName,
        )
    }

    override fun onCleared() {
        process?.stop()
    }

    companion object {
        fun exitLine(code: Int) = "\n[Program finished, exit code $code]\n"

        /** Splits segments on '\n' so the console can render line by line. */
        fun toLines(segments: List<Segment>): List<ConsoleLine> {
            val lines = ArrayList<ConsoleLine>()
            var current = ArrayList<Segment>()
            for (seg in segments) {
                val parts = seg.text.split('\n')
                parts.forEachIndexed { i, part ->
                    if (part.isNotEmpty()) current.add(Segment(part, seg.isError))
                    if (i < parts.lastIndex) {
                        lines.add(ConsoleLine(current))
                        current = ArrayList()
                    }
                }
            }
            if (current.isNotEmpty()) lines.add(ConsoleLine(current))
            return lines
        }
    }
}
