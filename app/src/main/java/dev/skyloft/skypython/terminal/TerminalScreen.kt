package dev.skyloft.skypython.terminal

import dev.skyloft.skypython.ui.theme.TextBright
import dev.skyloft.skypython.ui.theme.SlateRaised
import dev.skyloft.skypython.ui.theme.SlatePanel
import dev.skyloft.skypython.ui.theme.SlateLine
import dev.skyloft.skypython.ui.theme.OrangeTint
import dev.skyloft.skypython.ui.theme.OrangeSoft
import dev.skyloft.skypython.ui.theme.Orange
import dev.skyloft.skypython.ui.theme.OnOrange
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.Ink
import dev.skyloft.skypython.settings.clampTerminalFontSize
import dev.skyloft.skypython.settings.TerminalTheme
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.editor.codeTypeface
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import android.widget.Toast
import android.os.Build
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import dev.skyloft.skypython.ui.theme.CodeFont
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.skyloft.skypython.console.InstallError
import dev.skyloft.skypython.console.Installing
import dev.skyloft.skypython.console.InterruptButton
import dev.skyloft.skypython.ui.theme.SlateBar
import dev.skyloft.skypython.ui.theme.SkyTopBar
import dev.skyloft.skypython.ui.theme.brandButtonColors
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R

/**
 * A real terminal (VT100/xterm emulator on a pseudo-terminal) for the interpreter or the shell,
 * with a key row for the keys a phone keyboard lacks. The interpreter also gets a command line
 * ("Type a command…" and an orange send button) so a line can be edited comfortably before it runs.
 *
 * @param settings text size, colours and typeface (Settings → Terminal).
 * @param onFontSizeChange a pinch changed the text size (sp); persist it.
 * @param onUseSimpleConsole fallback when the pseudo-terminal cannot be created.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
    onUseSimpleConsole: () -> Unit,
    settings: EditorSettings = EditorSettings(),
    onFontSizeChange: (Int) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val background = terminalBackground(settings.terminalTheme)
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = background,
        contentColor = colors.onBackground,
        topBar = {
            SkyTopBar(
                title = stringResource(viewModel.titleRes),
                onBack = onBack,
                actions = {
                    if (state.running) {
                        InterruptButton(onClick = viewModel::interrupt)
                        IconButton(onClick = viewModel::kill) {
                            Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.action_stop), tint = colors.error)
                        }
                    } else if (state.session != null || state.startError != null) {
                        IconButton(onClick = viewModel::restart) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_restart))
                        }
                    }
                    if (state.session != null) {
                        IconButton(onClick = viewModel::clearScreen) {
                            Icon(Icons.Outlined.ClearAll, contentDescription = stringResource(R.string.terminal_clear))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.terminal_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_copy)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = {
                                        menuOpen = false
                                        copyTranscript(context, viewModel.transcript())
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_clear)) },
                                    leadingIcon = { Icon(Icons.Outlined.ClearAll, null) },
                                    onClick = { menuOpen = false; viewModel.clearScreen() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_restart)) },
                                    leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                    onClick = { menuOpen = false; viewModel.restart() },
                                )
                            }
                        }
                    }
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
                    state.startError != null -> StartError(state.startError!!, onUseSimpleConsole)
                    session != null -> TerminalPane(
                        session,
                        viewModel,
                        settings,
                        background,
                        // The interpreter types into its command line; the shell straight into the terminal.
                        focusOnOpen = !viewModel.isInterpreter,
                        onFontSizeChange = onFontSizeChange,
                    )
                    else -> Installing()
                }
            }
            if (viewModel.isInterpreter && state.session != null) {
                CommandLine(enabled = state.running, onSend = viewModel::sendLine)
            }
            KeyRow(
                modifiers = state.modifiers,
                enabled = state.running,
                onKey = viewModel::onExtraKey,
            )
        }
    }
}

private fun copyTranscript(context: Context, text: String?) {
    if (text.isNullOrEmpty()) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("SkyPython", text))
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, AppLocale.string(context, R.string.terminal_copied), Toast.LENGTH_SHORT).show()
    }
}

/** The terminal background for a theme: the app's deepest slate, or near-black. */
private fun terminalBackground(theme: TerminalTheme): Color = when (theme) {
    TerminalTheme.Dark -> Ink
    TerminalTheme.Black -> Color(0xFF0B0E12)
}

/**
 * The interpreter's command line: a rounded field and a round orange send button. Enter sends
 * too; an empty send is a plain Enter (handy after ↑ recalled a line in the terminal).
 */
@Composable
private fun CommandLine(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(enabled) { if (enabled) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(SlateBar)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = TextBright, fontFamily = CodeFont, fontSize = 15.sp),
            cursorBrush = SolidColor(Orange),
            // Code, not prose: no auto-capitals ("i" → "I") and no autocorrect.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .background(SlatePanel, shape)
                        .border(1.dp, SlateLine, shape)
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(stringResource(R.string.terminal_command_hint), color = MutedText, fontSize = 15.sp)
                    }
                    inner()
                }
            },
        )
        val sendLabel = stringResource(R.string.action_send)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(if (enabled) Orange else SlateRaised)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = sendLabel) { send() }
                .semantics { contentDescription = sendLabel },
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = null,
                tint = if (enabled) OnOrange else MutedText,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The emulator view; survives recomposition, re-attaches to a new session after Restart. */
@Composable
private fun TerminalPane(
    session: TerminalSession,
    viewModel: TerminalViewModel,
    settings: EditorSettings,
    background: Color,
    focusOnOpen: Boolean,
    onFontSizeChange: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val palette = TerminalPalette(
        foreground = colors.onBackground.toArgb(),
        background = background.toArgb(),
        cursor = Orange.toArgb(),
    )
    // Pinch changes it right away and saves it; Settings changes come back through [settings].
    var fontSp by remember(settings.terminalFontSizeSp) { mutableIntStateOf(settings.terminalFontSizeSp) }
    val fontPx = with(LocalDensity.current) { fontSp.sp.toPx() }.roundToInt()
    val zoom = { bigger: Boolean ->
        fontSp = clampTerminalFontSize(fontSp + if (bigger) 1 else -1)
        onFontSizeChange(fontSp)
    }
    val context = LocalContext.current
    val typeface = remember(settings.codeFont) { codeTypeface(context, settings.codeFont) }

    AndroidView(
        modifier = Modifier.fillMaxSize().padding(start = 4.dp),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                palette.applyDefaults()
                setTerminalViewClient(ViewClient(this, viewModel) { zoom(it) })
                isFocusable = true
                isFocusableInTouchMode = true
                setTypeface(typeface)
                setTextSize(fontPx)
                tag = fontPx to typeface
                attachSession(session)
                viewModel.attachView(this)
                if (focusOnOpen) {
                    post {
                        requestFocus()
                        showKeyboard(this)
                    }
                }
            }
        },
        update = { view ->
            palette.applyDefaults()
            palette.applyTo(session)
            if (view.tag != (fontPx to typeface)) {
                view.tag = fontPx to typeface
                view.setTypeface(typeface)
                view.setTextSize(fontPx)
            }
            if (view.currentSession !== session) {
                view.attachSession(session)
            }
            view.invalidate()
            viewModel.attachView(view)
        },
        onRelease = { view -> viewModel.detachView(view) },
    )
}

private fun showKeyboard(view: TerminalView) {
    val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

/**
 * The 16 ANSI colours, tuned to the slate background and the orange accent (the stock xterm
 * ones are pure, saturated primaries — e.g. the Python REPL's prompt came out bright magenta).
 * Order: black, red, green, yellow, blue, magenta, cyan, white, then the bright eight.
 */
private val ANSI_COLORS = longArrayOf(
    0xFF2A3442, 0xFFFF7B72, 0xFFA8D48C, 0xFFF5C26B, 0xFF7AB8F5, 0xFFC9A2F0, 0xFF6CCFC0, 0xFFC8CEDD,
    0xFF5C6B7C, 0xFFFF9B94, 0xFFBFE3A8, 0xFFFFD48F, 0xFF9CCBF8, 0xFFDABDF5, 0xFF8EDCD0, 0xFFF2F4F8,
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

/** Keyboard, touch and zoom handling for the terminal view. */
private class ViewClient(
    private val view: TerminalView,
    private val viewModel: TerminalViewModel,
    private val onZoom: (bigger: Boolean) -> Unit,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        // Pinch: one font step per 10 % of scaling.
        if (scale < 0.9f || scale > 1.1f) {
            onZoom(scale > 1f)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        showKeyboard(view)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        // "[Process completed - press Enter]": Enter starts it again.
        if (keyCode == KeyEvent.KEYCODE_ENTER && session != null && !session.isRunning) {
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
        if ((codePoint == '\r'.code || codePoint == '\n'.code) && session != null && !session.isRunning) {
            viewModel.restart()
            return true
        }
        return false
    }

    override fun onEmulatorSet() {
        view.invalidate()
    }

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
            .background(SlateBar)
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
                    containerColor = if (armed) OrangeTint else Color.Transparent,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .semantics { contentDescription = description },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    key.label,
                    fontFamily = CodeFont,
                    fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp,
                    color = when {
                        !enabled -> colors.onSurface.copy(alpha = 0.38f)
                        armed -> OrangeSoft
                        else -> colors.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun StartError(message: String, onUseSimpleConsole: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.terminal_open_failed), style = MaterialTheme.typography.titleMedium)
        Text(message, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error)
        Button(onClick = onUseSimpleConsole, colors = brandButtonColors()) { Text(stringResource(R.string.terminal_use_simple_console)) }
    }
}
