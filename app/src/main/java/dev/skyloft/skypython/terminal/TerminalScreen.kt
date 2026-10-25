package dev.skyloft.skypython.terminal

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import dev.skyloft.skypython.ui.theme.BarNavy
import dev.skyloft.skypython.ui.theme.SkyTopBar
import dev.skyloft.skypython.ui.theme.brandButtonColors
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R

private const val DEFAULT_FONT_SP = 14f
private const val MIN_FONT_SP = 8f
private const val MAX_FONT_SP = 32f

/**
 * A real terminal (VT100/xterm emulator on a pseudo-terminal) for the interpreter or the shell,
 * with a key row for the keys a phone keyboard lacks.
 *
 * @param onUseSimpleConsole fallback when the pseudo-terminal cannot be created.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
    onUseSimpleConsole: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            SkyTopBar(
                title = stringResource(viewModel.titleRes),
                onBack = onBack,
                actions = {
                    if (state.running) {
                        InterruptButton(onClick = viewModel::interrupt)
                        IconButton(onClick = viewModel::kill) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.action_stop), tint = colors.error)
                        }
                    } else if (state.session != null || state.startError != null) {
                        IconButton(onClick = viewModel::restart) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_restart))
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
                    session != null -> TerminalPane(session, viewModel)
                    else -> Installing()
                }
            }
            KeyRow(
                modifiers = state.modifiers,
                enabled = state.running,
                onKey = viewModel::onExtraKey,
            )
        }
    }
}

/** The emulator view; survives recomposition, re-attaches to a new session after Restart. */
@Composable
private fun TerminalPane(session: TerminalSession, viewModel: TerminalViewModel) {
    val colors = MaterialTheme.colorScheme
    val palette = TerminalPalette(
        foreground = colors.onBackground.toArgb(),
        background = colors.background.toArgb(),
        cursor = colors.secondary.toArgb(),
    )
    var fontSp by rememberSaveable { mutableFloatStateOf(DEFAULT_FONT_SP) }
    val fontPx = with(LocalDensity.current) { fontSp.sp.toPx() }.roundToInt()
    val zoom = { bigger: Boolean ->
        fontSp = (fontSp + if (bigger) 1f else -1f).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            TerminalView(context, null).apply {
                palette.applyDefaults()
                setTerminalViewClient(ViewClient(this, viewModel) { zoom(it) })
                isFocusable = true
                isFocusableInTouchMode = true
                setTextSize(fontPx)
                tag = fontPx
                attachSession(session)
                viewModel.attachView(this)
                post {
                    requestFocus()
                    showKeyboard(this)
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
                view.attachSession(session)
                view.invalidate()
            }
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
