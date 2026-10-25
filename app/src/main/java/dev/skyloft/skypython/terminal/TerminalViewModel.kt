package dev.skyloft.skypython.terminal

import android.app.Application
import androidx.annotation.StringRes
import dev.skyloft.skypython.R
import dev.skyloft.skypython.locale.AppLocale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

/** The programs that run on a real terminal. */
enum class TerminalKind(val title: String, @StringRes val titleRes: Int) {
    /** `python -i -q`: Python's own line editing, colours, Ctrl+C → KeyboardInterrupt. */
    Interpreter("Interpreter", R.string.title_interpreter),

    /** `/system/bin/sh -i` with `python` / `pip` defined (see ShellShims). */
    Shell("Terminal", R.string.title_terminal),

    /** `python -u <script>` in the script's folder (▶ Run); titled with the file name. */
    Run("Run", R.string.action_run),
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
    /** The script shown ([TerminalKind.Run] only). */
    val script: File? = null,
    /** A script asked to run while one is running; the UI asks before replacing it. */
    val pending: File? = null,
    /** How the last program ended (shell-style code), null while it runs or before. */
    val exitCode: Int? = null,
    /** Something was written to the screen (there is output to copy, share or save). */
    val hasOutput: Boolean = false,
    /** The newest output is in view; false once the user scrolled back. */
    val atBottom: Boolean = true,
    /** Terminal text size. */
    val fontSp: Float = DEFAULT_FONT_SP,
) {
    fun actions(kind: TerminalKind): OutputActions = OutputActions.of(kind, running, hasOutput)

    companion object {
        const val DEFAULT_FONT_SP = 14f
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 32f
    }
}

/** One-shot results of the output actions, for a toast or snackbar. */
sealed interface TerminalEvent {
    data class Message(val text: String) : TerminalEvent

    /** Share [text], or the file [file] when the text is too long for an intent. */
    data class Share(val title: String, val text: String?, val file: File?) : TerminalEvent

    /** A traceback line was tapped: open [file] at [line] (1-based) in the editor. */
    data class OpenInEditor(val file: File, val line: Int) : TerminalEvent
}

/**
 * Owns one terminal session ([kind]) for the whole activity: leaving the screen or rotating keeps
 * the program running. All calls on the main thread (TerminalSession posts to it).
 */
class TerminalViewModel(
    val kind: TerminalKind,
    private val projectsRoot: File,
    private val app: Application,
    private val install: suspend () -> Result<PythonPaths>,
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<TerminalEvent>>(emptyList())

    /** Pending [TerminalEvent]s; the screen shows them and calls [consumed]. */
    val events: StateFlow<List<TerminalEvent>> = _events.asStateFlow()

    private val _screenText = MutableStateFlow("")

    /**
     * The text on the screen right now (rows in view, without colours), updated at most every
     * [SCREEN_TEXT_MILLIS]: the terminal's accessibility text, and what UI tests read.
     */
    val screenText: StateFlow<String> = _screenText.asStateFlow()

    private var paths: PythonPaths? = null
    private var startJob: Job? = null

    /** The view showing the session, if any; screen updates go to it. Weak: views die on rotation. */
    private var viewRef: WeakReference<TerminalView>? = null
    private val view: TerminalView? get() = viewRef?.get()
    private val client = SessionClient()
    private val main = Handler(Looper.getMainLooper())
    private var screenTextPosted = false
    private val clipboard get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val title: String
        get() = _state.value.script?.name.takeIf { kind == TerminalKind.Run } ?: AppLocale.string(app, kind.titleRes)

    /** The localized [title]. */
    @get:StringRes val titleRes: Int get() = kind.titleRes

    /** Shows the running session, or starts a new one if there is none or it has ended. */
    fun open() {
        if (startJob?.isActive == true) return
        if (_state.value.running) return
        start()
    }

    /** Runs [script] now, ending whatever this terminal runs ([TerminalKind.Run] only). */
    fun run(script: File) {
        check(kind == TerminalKind.Run)
        _state.update { it.copy(script = script, pending = null) }
        restart()
    }

    /**
     * Only one script at a time: while one runs, [script] becomes [TerminalState.pending] and
     * nothing starts until [confirmPendingRun]. @return true if it started now.
     */
    fun requestRun(script: File): Boolean {
        if (_state.value.running || startJob?.isActive == true) {
            _state.update { it.copy(pending = script) }
            return false
        }
        run(script)
        return true
    }

    fun confirmPendingRun() {
        val script = _state.value.pending ?: return
        run(script)
    }

    fun dismissPendingRun() {
        _state.update { it.copy(pending = null) }
    }

    /** Ends the current session (if running) and starts a new one: Restart, Run again. */
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

    // ---- output actions -------------------------------------------------------------------

    /** Everything on the screen and in the scrollback as plain text ("" if nothing yet). */
    fun transcript(): String {
        val emulator = _state.value.session?.emulator ?: return ""
        return Transcript.of(emulator.screen)
    }

    /** Copy all: the transcript to the clipboard. */
    fun copyAll() {
        val text = transcript()
        if (text.isEmpty()) {
            message(R.string.output_nothing)
            return
        }
        copy(text)
    }

    /** Puts [text] on the clipboard (a selection's Copy, Copy all). */
    fun copy(text: String) {
        if (text.isEmpty()) return
        clipboard.setPrimaryClip(ClipData.newPlainText(title, text))
        // Android 13+ shows its own confirmation for every copy.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            message(R.string.output_copied)
        }
    }

    /** Share output (or [text], a selection): as text, or as a file when it is long. */
    fun share(text: String = transcript()) {
        if (text.isEmpty()) {
            message(R.string.output_nothing)
            return
        }
        viewModelScope.launch {
            val file = if (OutputFiles.shareAsFile(text)) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val dir = File(app.cacheDir, "run").apply { mkdirs() }
                        File(dir, OutputFiles.name(title)).apply { writeText(text) }
                    }
                }.getOrNull()
            } else {
                null
            }
            emit(TerminalEvent.Share(title, if (file == null) text else null, file))
        }
    }

    /**
     * Save output: `<script>_output.txt` next to the script (the interpreter's and the shell's go
     * to the projects folder), never over an earlier one.
     */
    fun saveOutput(onSaved: (File) -> Unit = {}) {
        val text = transcript()
        if (text.isEmpty()) {
            message(R.string.output_nothing)
            return
        }
        val dir = _state.value.script?.absoluteFile?.parentFile ?: projectsRoot
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    OutputFiles.unique(dir, OutputFiles.name(title)).apply { writeText(text + "\n") }
                }
            }
            result.onSuccess { file ->
                message(AppLocale.string(app, R.string.output_saved, file.relativeToOrSelf(projectsRoot.parentFile ?: projectsRoot).path))
                onSaved(file)
            }.onFailure { e ->
                message(AppLocale.string(app, R.string.output_save_failed, e.message ?: e.toString()))
            }
        }
    }

    /** The name "Save as…" suggests. */
    fun suggestedOutputName(): String = OutputFiles.name(title)

    /** Save as…: writes the transcript to a document the user picked. */
    fun saveOutputTo(uri: Uri) {
        val text = transcript()
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException(uri.toString())
                    out.use { it.write((text + "\n").toByteArray(Charsets.UTF_8)) }
                }
            }
            result.onSuccess { message(AppLocale.string(app, R.string.output_saved, uri.lastPathSegment ?: "")) }
                .onFailure { e -> message(AppLocale.string(app, R.string.output_save_failed, e.message ?: e.toString())) }
        }
    }

    /**
     * Clear: empties the screen and the scrollback on our side. The interpreter and the shell also
     * get Ctrl+L so they draw their prompt again; a script is left alone.
     */
    fun clear() {
        val session = _state.value.session ?: return
        val emulator = session.emulator ?: return
        val reset = "\u001b[H\u001b[2J\u001b[3J".toByteArray()
        emulator.append(reset, reset.size)
        view?.stopTextSelectionMode()
        view?.scrollToBottom()
        view?.onScreenUpdated()
        _state.update { it.copy(hasOutput = false) }
        postScreenText()
        if (kind != TerminalKind.Run && session.isRunning) write("\u000c")
    }

    fun scrollToTop() {
        view?.scrollToTop()
    }

    fun scrollToBottom() {
        view?.scrollToBottom()
    }

    /** Text size one step bigger or smaller (the overflow menu, pinch). */
    fun zoom(bigger: Boolean) {
        _state.update {
            val sp = (it.fontSp + if (bigger) 1f else -1f)
                .coerceIn(TerminalState.MIN_FONT_SP, TerminalState.MAX_FONT_SP)
            it.copy(fontSp = sp)
        }
    }

    /**
     * A tap on screen row [row] (scrollback rows are negative): if it is a traceback's
     * `File "…", line N` for a file in the projects, asks to open it there. @return true if so.
     */
    fun openTracebackAt(row: Int): Boolean {
        val screen = _state.value.session?.emulator?.screen ?: return false
        if (row < -screen.activeTranscriptRows || row >= screen.activeRows - screen.activeTranscriptRows) return false
        val location = Tracebacks.parse(Transcript.logicalLine(screen, row)) ?: return false
        val cwd = _state.value.script?.absoluteFile?.parentFile ?: projectsRoot
        val file = Tracebacks.resolve(location, cwd, projectsRoot) ?: return false
        emit(TerminalEvent.OpenInEditor(file, location.line))
        return true
    }

    fun consumed(event: TerminalEvent) {
        _events.update { it - event }
    }

    fun attachView(v: TerminalView) {
        if (view !== v) viewRef = WeakReference(v)
    }

    fun detachView(v: TerminalView) {
        if (view === v) viewRef = null
    }

    internal fun onScrolledToBottomChanged(atBottom: Boolean) {
        _state.update { it.copy(atBottom = atBottom) }
    }

    private fun message(@StringRes res: Int) = message(AppLocale.string(app, res))

    private fun message(text: String) = emit(TerminalEvent.Message(text))

    private fun emit(event: TerminalEvent) {
        _events.update { it + event }
    }

    private fun write(text: String) {
        val session = _state.value.session ?: return
        if (!session.isRunning) return
        view?.scrollToBottom()
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private fun command(): RunCommand = when (kind) {
        TerminalKind.Interpreter -> RunCommand.Interpreter(projectsRoot)
        TerminalKind.Shell -> RunCommand.Shell(projectsRoot)
        TerminalKind.Run -> RunCommand.Script(checkNotNull(_state.value.script) { "No script to run" })
    }

    private fun start() {
        if (kind == TerminalKind.Run && _state.value.script == null) return
        startJob = viewModelScope.launch {
            _state.update { it.copy(installError = null, startError = null, exitCode = null) }
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
                withContext(Dispatchers.IO) { PtyCommand.prepare(p, command()) }
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
            if (kind == TerminalKind.Run) {
                session.setExitMessage { status, atLineStart ->
                    RunFooter.text(status, atLineStart) { AppLocale.string(app, R.string.console_exit_footer, it) }
                }
            }
            _state.update {
                it.copy(session = session, running = true, modifiers = Modifiers(), hasOutput = false, atBottom = true)
            }
            _screenText.value = ""
        }
    }

    private fun postScreenText() {
        if (screenTextPosted) return
        screenTextPosted = true
        main.postDelayed({
            screenTextPosted = false
            val emulator = _state.value.session?.emulator
            _screenText.value = if (emulator == null) "" else
                emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows - 1).trimEnd()
        }, SCREEN_TEXT_MILLIS)
    }

    override fun onCleared() {
        main.removeCallbacksAndMessages(null)
        _state.value.session?.finishIfRunning()
    }

    private inner class SessionClient : TerminalSessionClient {

        override fun onTextChanged(changedSession: TerminalSession) {
            if (changedSession !== _state.value.session) return
            view?.onScreenUpdated()
            if (!_state.value.hasOutput) _state.update { it.copy(hasOutput = true) }
            postScreenText()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (finishedSession !== _state.value.session) return
            _state.update {
                it.copy(
                    running = false,
                    startError = finishedSession.startError,
                    exitCode = RunFooter.exitCode(finishedSession.exitStatus),
                    modifiers = Modifiers(),
                )
            }
            postScreenText()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (!text.isNullOrEmpty()) copy(text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            if (session?.isRunning != true) return
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(app)?.toString()
            if (!text.isNullOrEmpty()) {
                view?.scrollToBottom()
                session.emulator?.paste(text)
            }
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

        /** How often [screenText] may change. */
        private const val SCREEN_TEXT_MILLIS = 100L

        fun factory(app: Application, projectsRoot: File, kind: TerminalKind): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val installer = PythonInstaller(app)
                    TerminalViewModel(kind, projectsRoot, app, installer::ensureInstalled)
                }
            }
    }
}
