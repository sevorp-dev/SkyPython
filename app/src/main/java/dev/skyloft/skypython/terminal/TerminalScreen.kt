package dev.skyloft.skypython.terminal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.skyloft.skypython.R
import dev.skyloft.skypython.console.InstallError
import dev.skyloft.skypython.console.Installing
import dev.skyloft.skypython.console.InterruptButton
import dev.skyloft.skypython.ui.theme.BarNavy
import dev.skyloft.skypython.ui.theme.SkyTopBar
import kotlinx.coroutines.launch
import java.io.File
import dev.skyloft.skypython.ui.theme.brandButtonColors
import kotlin.math.roundToInt

/** Test tag of the terminal; its semantics text is what is on the screen. */
const val TERMINAL_TAG = "terminal"

/**
 * A real terminal (VT100/xterm emulator on a pseudo-terminal) for a script (▶ Run), the
 * interpreter or the shell, with a key row for the keys a phone keyboard lacks and the output
 * actions: copy, share, save, clear, scroll, text size. Long-press selects text.
 *
 * @param onUseSimpleConsole fallback when the pseudo-terminal cannot be created.
 * @param onOpenInEditor a traceback's `File "…", line N` was tapped.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
    onUseSimpleConsole: () -> Unit,
    onOpenInEditor: (File, Int) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val kind = viewModel.kind
    val actions = state.actions(kind)
    var view by remember { mutableStateOf<TerminalView?>(null) }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.saveOutputTo(uri)
    }

    val currentOpenInEditor by rememberUpdatedState(onOpenInEditor)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { pending ->
            for (event in pending) {
                viewModel.consumed(event)
                when (event) {
                    // Not awaited: the next event must not wait for the snackbar to go.
                    is TerminalEvent.Message -> launch { snackbar.showSnackbar(event.text) }
                    is TerminalEvent.Share -> shareOutput(context, event)
                    is TerminalEvent.OpenInEditor -> currentOpenInEditor(event.file, event.line)
                }
            }
        }
    }

    // A finished script reads no input: put the keyboard away so the footer's actions show.
    LaunchedEffect(state.running, view) {
        if (!state.running && kind == TerminalKind.Run) view?.let(::hideKeyboard)
    }

    Scaffold(
        containerColor = colors.background,
        contentColor = colors.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SkyTopBar(
                title = viewModel.title,
                onBack = onBack,
                actions = {
                    TopActions(viewModel, actions, state, onSaveAs = { saveAs.launch(viewModel.suggestedOutputName()) })
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                // The terminal shrinks above the keyboard; the program is told its new size.
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val session = state.session
                when {
                    state.installError != null -> InstallError(state.installError!!, viewModel::restart)
                    state.startError != null -> StartError(state.startError!!, kind, onUseSimpleConsole)
                    session != null -> TerminalPane(session, viewModel, state.fontSp) { view = it }
                    else -> Installing()
                }
                if (!state.atBottom && state.session != null) {
                    SmallFloatingActionButton(
                        onClick = viewModel::scrollToBottom,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.output_scroll_bottom))
                    }
                }
            }
            if (kind == TerminalKind.Run && !state.running && state.exitCode != null) {
                FinishedBar(
                    exitCode = state.exitCode!!,
                    onRunAgain = viewModel::restart,
                    onBackToEditor = onBack,
                )
            } else {
                KeyRow(
                    modifiers = state.modifiers,
                    enabled = state.running,
                    onKey = viewModel::onExtraKey,
                )
            }
        }
    }
}

/** ^C and Stop while running, Run again / Restart after; Copy all; the rest in ⋮. */
@Composable
private fun TopActions(
    viewModel: TerminalViewModel,
    actions: OutputActions,
    state: TerminalState,
    onSaveAs: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (actions.canInterrupt) {
        InterruptButton(onClick = viewModel::interrupt)
        IconButton(onClick = viewModel::kill) {
            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.action_stop), tint = colors.error)
        }
    } else if (actions.canRunAgain) {
        IconButton(onClick = viewModel::restart) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.run_again))
        }
    } else if (actions.canRestart && (state.session != null || state.startError != null)) {
        IconButton(onClick = viewModel::restart) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_restart))
        }
    }
    IconButton(onClick = viewModel::copyAll, enabled = actions.canCopy) {
        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.output_copy_all))
    }
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.output_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item = @Composable { label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    enabled = enabled,
                    onClick = { open = false; onClick() },
                )
            }
            item(R.string.output_copy_all, Icons.Filled.ContentCopy, actions.canCopy) { viewModel.copyAll() }
            item(R.string.output_share, Icons.Filled.Share, actions.canShare) { viewModel.share() }
            item(R.string.output_save, Icons.Filled.Save, actions.canSave) { viewModel.saveOutput() }
            item(R.string.output_save_as, Icons.Filled.SaveAs, actions.canSave) { onSaveAs() }
            item(R.string.output_clear, Icons.Filled.DeleteSweep, actions.canClear) { viewModel.clear() }
            HorizontalDivider()
            item(R.string.output_scroll_top, Icons.Filled.VerticalAlignTop, state.session != null) { viewModel.scrollToTop() }
            item(R.string.output_scroll_bottom, Icons.Filled.VerticalAlignBottom, state.session != null) { viewModel.scrollToBottom() }
            HorizontalDivider()
            // Text size: − and + side by side, the size between them; the menu stays open.
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { viewModel.zoom(bigger = false) },
                    enabled = state.fontSp > TerminalState.MIN_FONT_SP,
                ) {
                    Icon(Icons.Filled.TextDecrease, contentDescription = stringResource(R.string.output_font_smaller))
                }
                Text(
                    stringResource(R.string.output_font_size, state.fontSp.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(min = 72.dp),
                )
                IconButton(
                    onClick = { viewModel.zoom(bigger = true) },
                    enabled = state.fontSp < TerminalState.MAX_FONT_SP,
                ) {
                    Icon(Icons.Filled.TextIncrease, contentDescription = stringResource(R.string.output_font_bigger))
                }
            }
        }
    }
}

/** Under a finished script: how it ended, Run again, Back to editor. */
@Composable
private fun FinishedBar(exitCode: Int, onRunAgain: () -> Unit, onBackToEditor: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.run_exit_code, exitCode),
            style = MaterialTheme.typography.labelLarge,
            color = if (exitCode == 0) colors.onSurfaceVariant else colors.error,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onBackToEditor, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.back_to_editor))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRunAgain, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.run_again))
        }
    }
}

/** The emulator view; survives recomposition, re-attaches to a new session after Restart. */
@Composable
private fun TerminalPane(
    session: TerminalSession,
    viewModel: TerminalViewModel,
    fontSp: Float,
    onView: (TerminalView?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val palette = TerminalPalette(
        foreground = colors.onBackground.toArgb(),
        background = colors.background.toArgb(),
        cursor = colors.secondary.toArgb(),
    )
    val fontPx = with(LocalDensity.current) { fontSp.sp.toPx() }.roundToInt()
    val screenText by viewModel.screenText.collectAsStateWithLifecycle()

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TERMINAL_TAG)
            .semantics { text = AnnotatedString(screenText) },
        factory = { context ->
            TerminalView(context, null).apply {
                palette.applyDefaults()
                setTerminalViewClient(ViewClient(this, viewModel))
                isFocusable = true
                isFocusableInTouchMode = true
                setTextSize(fontPx)
                tag = fontPx
                attachSession(session)
                viewModel.attachView(this)
                onView(this)
                post {
                    requestFocus()
                    if (viewModel.state.value.running) showKeyboard(this)
                }
            }
        },
        update = { view ->
            palette.applyDefaults()
            palette.applyTo(session)
            if (view.tag != fontPx) {
                view.tag = fontPx
                view.setTextSize(fontPx)
            }
            if (view.currentSession !== session) {
                view.stopTextSelectionMode()
                view.attachSession(session)
                view.invalidate()
                view.requestFocus()
            }
            viewModel.attachView(view)
        },
        onRelease = { view ->
            viewModel.detachView(view)
            onView(null)
        },
    )
}

private fun showKeyboard(view: TerminalView) {
    val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

private fun hideKeyboard(view: TerminalView) {
    val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}

/** Share output: plain text, or a text file through the FileProvider when it is long. */
private fun shareOutput(context: Context, event: TerminalEvent.Share) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, event.title)
        if (event.file != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", event.file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            putExtra(Intent.EXTRA_TEXT, event.text)
        }
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.output_share)).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
    }
}
/**
 * The 16 ANSI colours, softened to sit calmly on the navy background (the stock xterm ones are
 * pure, saturated primaries — e.g. the Python REPL's prompt came out bright magenta).
 */
private val ANSI_COLORS = longArrayOf(
    0xFF2A3450, 0xFFF0808F, 0xFF7FD1A0, 0xFFE8CB8A, 0xFF82A8F0, 0xFFB8A6EE, 0xFF72C6E4, 0xFFC8CEDD,
    0xFF5E6985, 0xFFFF9AA6, 0xFF9BE0B5, 0xFFF2D9A2, 0xFFA3C0F7, 0xFFCDBFF5, 0xFF96D6EE, 0xFFF2F4F8,
).let { argb -> IntArray(argb.size) { argb[it].toInt() } }

/** The app theme's colours as the terminal's default foreground, background and cursor. */
private data class TerminalPalette(val foreground: Int, val background: Int, val cursor: Int) {
    /** For emulators created from now on, and for a full reset (`ESC c`). */
    fun applyDefaults() {
        val d = TerminalColors.COLOR_SCHEME.mDefaultColors
        ANSI_COLORS.copyInto(d)
        d[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
        d[TextStyle.COLOR_INDEX_BACKGROUND] = background
        d[TextStyle.COLOR_INDEX_CURSOR] = cursor
    }

    /** For an emulator that already exists (colours a program set itself are kept). */
    fun applyTo(session: TerminalSession) {
        val c = session.emulator?.mColors?.mCurrentColors ?: return
        ANSI_COLORS.copyInto(c)
        c[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
        c[TextStyle.COLOR_INDEX_BACKGROUND] = background
        c[TextStyle.COLOR_INDEX_CURSOR] = cursor
    }
}

/** Keyboard, touch, selection and zoom handling for the terminal view. */
private class ViewClient(
    private val view: TerminalView,
    private val viewModel: TerminalViewModel,
) : TerminalViewClient {

    private val running get() = viewModel.state.value.running

    override fun onScale(scale: Float): Float {
        // Pinch: one font step per 10 % of scaling.
        if (scale < 0.9f || scale > 1.1f) {
            viewModel.zoom(bigger = scale > 1f)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        // A traceback's `File "…", line N` opens the editor there; elsewhere a tap brings up the
        // keyboard while the program can read it.
        if (e != null && viewModel.openTracebackAt(view.getColumnAndRow(e, true)[1])) return
        if (running) showKeyboard(view)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit

    /** "[Process completed - press Enter]" of the interpreter and the shell: Enter starts it again. */
    private fun restartsOnEnter(session: TerminalSession?) =
        viewModel.kind != TerminalKind.Run && session != null && !session.isRunning

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && restartsOnEnter(session)) {
            viewModel.restart()
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false

    override fun readControlKey() = viewModel.consumeCtrl()
    override fun readAltKey() = viewModel.consumeAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if ((codePoint == '\r'.code || codePoint == '\n'.code) && restartsOnEnter(session)) {
            viewModel.restart()
            return true
        }
        return false
    }

    override fun onEmulatorSet() {
        view.invalidate()
    }

    // Selection menu.
    override fun canPaste() = running
    override fun onCopyAll() = viewModel.copyAll()
    override fun onShareText(text: String?) = viewModel.share(text.orEmpty())
    override fun onScrolledToBottomChanged(atBottom: Boolean) = viewModel.onScrolledToBottomChanged(atBottom)

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

/** ESC TAB CTRL ALT - / | ← ↑ ↓ →, scrollable on narrow screens; armed modifiers are highlighted. */
@Composable
private fun KeyRow(modifiers: Modifiers, enabled: Boolean, onKey: (ExtraKey) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(BarNavy)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in ExtraKey.ROW) {
            val description = stringResource(key.descriptionRes)
            val armed = (key == ExtraKey.Ctrl && modifiers.ctrl) || (key == ExtraKey.Alt && modifiers.alt)
            TextButton(
                onClick = { onKey(key) },
                enabled = enabled,
                // An armed Ctrl / Alt sits on a tonal pill, so it reads as "on" at a glance.
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (armed) colors.primaryContainer else Color.Transparent,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .semantics { contentDescription = description },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    key.label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp,
                    color = when {
                        !enabled -> colors.onSurface.copy(alpha = 0.38f)
                        armed -> colors.onPrimaryContainer
                        else -> colors.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun StartError(message: String, kind: TerminalKind, onUseSimpleConsole: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.terminal_open_failed), style = MaterialTheme.typography.titleMedium)
        Text(message, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error)
        Button(onClick = onUseSimpleConsole, colors = brandButtonColors()) {
            Text(stringResource(if (kind == TerminalKind.Run) R.string.use_simple_output else R.string.terminal_use_simple_console))
        }
    }
}
