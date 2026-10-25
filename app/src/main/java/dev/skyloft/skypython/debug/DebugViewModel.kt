package dev.skyloft.skypython.debug

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.skyloft.skypython.R
import dev.skyloft.skypython.console.RunTarget
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.runtime.DebugSocket
import dev.skyloft.skypython.runtime.DebuggerInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The debugger for the app: breakpoints and watches ([store], kept across restarts) and the
 * current [DebugSession]. The program itself runs in the console ([RunTarget.Debug]), so output,
 * input and "one program at a time" work as for a normal run; this side connects to the
 * debugger driver inside it.
 */
class DebugViewModel(
    private val app: Application,
    /** Code under these directories is the user's: stepping stops only there. */
    private val projectsRoot: File,
) : ViewModel() {

    private val installer = DebuggerInstaller(app)
    private val stateFile = File(app.filesDir, STATE_FILE)
    private val saves = Channel<String>(Channel.CONFLATED)

    val store = BreakpointStore(runCatching { stateFile.takeIf { it.isFile }?.readText() }.getOrNull()) { saves.trySend(it) }
    private val session = DebugSession(viewModelScope)
    val state: StateFlow<DebugState> = session.state

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (json in saves) {
                runCatching {
                    val part = File(stateFile.path + ".part")
                    part.writeText(json)
                    if (!part.renameTo(stateFile)) {
                        stateFile.delete()
                        part.renameTo(stateFile)
                    }
                }
            }
        }
        viewModelScope.launch { store.watches.collect { session.setWatches(it) } }
    }

    /** Installs the driver and picks a socket for debugging [file]; null (and an error shown) on failure. */
    suspend fun prepare(file: File): RunTarget.Debug? {
        val driver = installer.ensureInstalled()
        return driver.fold(
            onSuccess = { RunTarget.Debug(file, it, installer.newChannel()) },
            onFailure = { e ->
                session.begin(file.name)
                session.fail(AppLocale.string(app, R.string.debug_error_start, e.message ?: e.toString()))
                null
            },
        )
    }

    /**
     * [target] was started in the console: connects to its driver while [isActive] says the
     * program is still starting or running, then hands breakpoints and watches over.
     */
    fun attach(target: RunTarget.Debug, isActive: () -> Boolean) {
        session.begin(target.file.name)
        viewModelScope.launch {
            val socket = withContext(Dispatchers.IO) {
                try {
                    Result.success(DebugSocket.connect(target.channel, CONNECT_TIMEOUT_MS, isActive))
                } catch (e: IOException) {
                    Result.failure(e)
                } finally {
                    target.channel.delete()
                }
            }
            socket.fold(
                onSuccess = {
                    val roots = listOfNotNull(
                        projectsRoot.path,
                        target.file.absoluteFile.parentFile?.path,
                    ).distinct()
                    session.attach(it, store.state.value, store.watches.value, roots)
                },
                onFailure = { e ->
                    session.fail(AppLocale.string(app, R.string.debug_error_start, e.message ?: e.toString()))
                },
            )
        }
    }

    fun toggleBreakpoint(path: String, line: Int) {
        store.toggle(path, line)
        syncBreakpoints(path)
    }

    fun setCondition(path: String, line: Int, condition: String?) {
        store.setCondition(path, line, condition)
        syncBreakpoints(path)
    }

    /** Lines added or removed in [path]: breakpoints follow their code. Not sent to a running
     *  program, whose code doesn't change until it is run again. */
    fun onLinesEdited(path: String, edit: LineEdit) = store.applyEdit(path, edit)

    private fun syncBreakpoints(path: String) {
        if (session.state.value.isLive) session.updateBreakpoints(path, store.state.value[path].orEmpty())
    }

    fun resume(step: Step) = session.resume(step)
    fun pause() = session.pause()
    fun selectFrame(index: Int) = session.selectFrame(index)
    fun toggleExpanded(ref: Int) = session.toggleExpanded(ref)
    fun addWatch(expression: String) = store.addWatch(expression)
    fun removeWatch(index: Int) = store.removeWatch(index)

    /** Ends the program: the driver exits at once; [killProcess] is the fallback. */
    fun stop(killProcess: () -> Unit) {
        session.stop()
        killProcess()
    }

    /** Hides the panel of an ended session. */
    fun close() = session.close()

    override fun onCleared() {
        session.close()
    }

    companion object {
        private const val STATE_FILE = "debugger.json"
        /** Long enough for the first run, which unpacks Python before anything starts. */
        private const val CONNECT_TIMEOUT_MS = 180_000L

        fun factory(app: Application, projectsRoot: File): ViewModelProvider.Factory = viewModelFactory {
            initializer { DebugViewModel(app, projectsRoot) }
        }
    }
}
