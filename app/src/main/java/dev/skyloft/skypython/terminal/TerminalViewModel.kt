package dev.skyloft.skypython.terminal

import android.app.Application
import androidx.annotation.StringRes
import dev.skyloft.skypython.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import dev.skyloft.skypython.runtime.PtyCommand
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.RunCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

/** The two programs that run on a real terminal. */
enum class TerminalKind(val title: String, @StringRes val titleRes: Int) {
    /** `python -i -q`: Python's own line editing, colours, Ctrl+C → KeyboardInterrupt. */
    Interpreter("Interpreter", R.string.title_interpreter),

    /** `/system/bin/sh -i` with `python` / `pip` defined (see ShellShims). */
    Shell("Terminal", R.string.title_terminal),
    ;

    fun command(projectsRoot: File): RunCommand = when (this) {
        Interpreter -> RunCommand.Interpreter(projectsRoot)
        Shell -> RunCommand.Shell(projectsRoot)
    }
}

data class TerminalState(
    val installing: Boolean = false,
    val installError: String? = null,
    /** The session on screen; it stays after its process ends so the output can still be read. */
    val session: TerminalSession? = null,
    /** True while the session's process is alive. */
    val running: Boolean = false,
    /** The pseudo-terminal could not be created (the simple console still works). */
    val startError: String? = null,
    /** CTRL / ALT armed from the key row. */
    val modifiers: Modifiers = Modifiers(),
)

/**
 * Owns one terminal session ([kind]) for the whole activity: leaving the screen or rotating keeps
 * the program running. All calls on the main thread (TerminalSession posts to it).
 */
class TerminalViewModel(
    private val kind: TerminalKind,
    private val projectsRoot: File,
    private val app: Application,
    private val install: suspend () -> Result<PythonPaths>,
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private var paths: PythonPaths? = null
    private var startJob: Job? = null

    /** The view showing the session, if any; screen updates go to it. Weak: views die on rotation. */
    private var viewRef: WeakReference<TerminalView>? = null
    private val view: TerminalView? get() = viewRef?.get()
    private val client = SessionClient()

    val title: String get() = kind.title

    /** The localized [title]. */
    @get:StringRes val titleRes: Int get() = kind.titleRes

    /** Shows the running session, or starts a new one if there is none or it has ended. */
    fun open() {
        if (startJob?.isActive == true) return
        if (_state.value.running) return
        start()
    }

    /** Ends the current session (if running) and starts a new one. */
    fun restart() {
        _state.value.session?.finishIfRunning()
        startJob?.cancel()
        start()
    }

    /** Kills the program (SIGKILL to the shell / interpreter; the terminal hangs up its children). */
    fun kill() {
        _state.value.session?.finishIfRunning()
    }

    /** Ctrl+C: the PTY turns it into SIGINT for the foreground program. */
    fun interrupt() {
        write(TerminalKeys.CTRL_C)
    }

    /** A key from the key row. */
    fun onExtraKey(key: ExtraKey) {
        if (key.isModifier) {
            _state.update { it.copy(modifiers = it.modifiers.toggle(key)) }
            return
        }
        val emulator = _state.value.session?.emulator
        val seq = TerminalKeys.sequence(
            key,
            _state.value.modifiers,
            cursorApp = emulator?.isCursorKeysApplicationMode == true,
            keypadApp = emulator?.isKeypadApplicationMode == true,
        ) ?: return
        _state.update { it.copy(modifiers = it.modifiers.afterKey()) }
        write(seq)
    }

    /** Reads (and releases) CTRL for a key typed on the keyboard. */
    fun consumeCtrl(): Boolean {
        val on = _state.value.modifiers.ctrl
        if (on) _state.update { it.copy(modifiers = it.modifiers.copy(ctrl = false)) }
        return on
    }

    /** Reads (and releases) ALT for a key typed on the keyboard. */
    fun consumeAlt(): Boolean {
        val on = _state.value.modifiers.alt
        if (on) _state.update { it.copy(modifiers = it.modifiers.copy(alt = false)) }
        return on
    }

    fun attachView(v: TerminalView) {
        if (view !== v) viewRef = WeakReference(v)
    }

    fun detachView(v: TerminalView) {
        if (view === v) viewRef = null
    }

    private fun write(text: String) {
        val session = _state.value.session ?: return
        if (!session.isRunning) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private fun start() {
        startJob = viewModelScope.launch {
            _state.update { it.copy(installError = null, startError = null) }
            val p = paths ?: run {
                _state.update { it.copy(installing = true) }
                val result = install()
                _state.update { it.copy(installing = false) }
                result.getOrElse { e ->
                    _state.update { it.copy(installError = e.message ?: e.toString()) }
                    return@launch
                }.also { paths = it }
            }
            val command = try {
                withContext(Dispatchers.IO) { PtyCommand.prepare(p, kind.command(projectsRoot)) }
            } catch (e: IOException) {
                _state.update { it.copy(startError = e.message ?: e.toString()) }
                return@launch
            }
            // The process itself starts when the view first lays the session out (it needs the size).
            val session = TerminalSession(
                command.executable,
                command.cwd.path,
                command.argv.toTypedArray(),
                command.env.toTypedArray(),
                TRANSCRIPT_ROWS,
                client,
            )
            _state.update { it.copy(session = session, running = true, modifiers = Modifiers()) }
        }
    }

    override fun onCleared() {
        _state.value.session?.finishIfRunning()
    }

    private inner class SessionClient : TerminalSessionClient {
        private val clipboard get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        override fun onTextChanged(changedSession: TerminalSession) {
            view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (finishedSession !== _state.value.session) return
            _state.update { it.copy(running = false, startError = finishedSession.startError) }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            clipboard.setPrimaryClip(ClipData.newPlainText(kind.title, text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(app)?.toString()
            if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            view?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String?, message: String?) {
            Log.e(tag, message.orEmpty())
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(tag, message.orEmpty())
        }

        override fun logInfo(tag: String?, message: String?) {
            Log.i(tag, message.orEmpty())
        }

        override fun logDebug(tag: String?, message: String?) {
            Log.d(tag, message.orEmpty())
        }

        override fun logVerbose(tag: String?, message: String?) {
            Log.v(tag, message.orEmpty())
        }

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag, message.orEmpty(), e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag, "", e)
        }
    }

    companion object {
        /** Scrollback kept per session. */
        const val TRANSCRIPT_ROWS = 2000

        fun factory(app: Application, projectsRoot: File, kind: TerminalKind): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val installer = PythonInstaller(app)
                    TerminalViewModel(kind, projectsRoot, app, installer::ensureInstalled)
                }
            }
    }
}
