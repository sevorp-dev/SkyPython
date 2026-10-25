package dev.skyloft.skypython.console

import dev.skyloft.skypython.ui.theme.CodeFont
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R
import dev.skyloft.skypython.runTargetTitle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.ui.theme.SlateLine
import dev.skyloft.skypython.ui.theme.SlateBar
import dev.skyloft.skypython.ui.theme.ErrorRed
import androidx.compose.material3.HorizontalDivider
import dev.skyloft.skypython.ui.theme.Ink
import dev.skyloft.skypython.ui.theme.Orange
import dev.skyloft.skypython.ui.theme.brandButtonColors
import dev.skyloft.skypython.ui.theme.SkyTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/** Terminal-like colours for the output area, independent of the app theme. */
private val ConsoleBackground = Ink
private val ConsoleText = Color(0xFFDCE2EA)
private val ConsoleDim = Color(0xFF7A8797)
private val ConsolePrompt = Orange

private val ConsoleTextStyle = TextStyle(
    color = ConsoleText,
    fontFamily = CodeFont,
    fontSize = 14.sp,
    lineHeight = 19.sp,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(viewModel: ConsoleViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = ConsoleBackground,
        contentColor = ConsoleText,
        topBar = {
            SkyTopBar(
                title = consoleTitle(state.title),
                onBack = onBack,
                actions = {
                    if (state.running) {
                        // Gentle first: KeyboardInterrupt lets the script clean up; Stop kills it.
                        if (state.acceptsInput) InterruptButton(onClick = viewModel::interrupt)
                        IconButton(onClick = viewModel::stop) {
                            Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.action_stop), tint = ErrorRed)
                        }
                    } else {
                        IconButton(onClick = viewModel::restart) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_restart))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                // Scaffold already applied the nav-bar inset; don't count it again for the IME.
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.installError != null -> InstallError(state.installError!!, viewModel::retryInstall)
                    state.installing -> Installing()
                    else -> ConsoleOutput(state.lines, state.version, statusLineRegex())
                }
            }
            HorizontalDivider(color = SlateLine)
            InputRow(
                enabled = state.acceptsInput,
                // The REPL and the shell always want typing; a script may never read input, so the
                // keyboard would only hide its output.
                autoFocus = state.title == RunTarget.Interpreter.title || state.title == RunTarget.Terminal.title,
                onSend = viewModel::send,
            )
        }
    }
}

/**
 * Whether the viewport counts as "at the bottom", i.e. new output should keep following.
 * [slack] items of tolerance so a nearly-at-the-end user still follows.
 */
internal fun isNearBottom(lastVisibleIndex: Int, totalItems: Int, slack: Int = 1): Boolean =
    totalItems == 0 || lastVisibleIndex >= totalItems - 1 - slack

@Composable
private fun ConsoleOutput(lines: List<ConsoleLine>, version: Long, statusLine: Regex) {
    val listState = rememberLazyListState()
    // Follow output until the user scrolls away from the bottom; resume when they return.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val info = listState.layoutInfo
                follow = isNearBottom(
                    lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                    totalItems = info.totalItemsCount,
                )
            }
    }
    // Keyed on version, not line count: the count is constant at the cap, or when only
    // the last line grows.
    LaunchedEffect(version) {
        if (lines.isNotEmpty() && follow && !listState.isScrollInProgress) {
            listState.scrollToItem(lines.lastIndex)
            listState.scrollBy(100_000f) // to the end of a multi-row (wrapped) last line
        }
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(text = remember(line, statusLine) { line.toAnnotated(statusLine) }, style = ConsoleTextStyle)
            }
        }
    }
}

/** The console title: the script name, or the localized program name. */
@Composable
private fun consoleTitle(title: String?): String = when (title) {
    null -> stringResource(R.string.title_console)
    RunTarget.Interpreter.title -> runTargetTitle(RunTarget.Interpreter)
    RunTarget.Terminal.title -> runTargetTitle(RunTarget.Terminal)
    else -> title
}

/**
 * The console's own status lines (exit footer, "[Cancelled]"), in English and in the current
 * language, dimmed so program output stands out.
 */
@Composable
private fun statusLineRegex(): Regex {
    val footer = stringResource(R.string.console_exit_footer)
    val cancelled = stringResource(R.string.console_cancelled)
    return remember(footer, cancelled) {
        statusLineRegex(
            // English too: lines printed before a language switch stay dimmed.
            footers = listOf(footer, "[Program finished, exit code %1\$d]"),
            cancelled = listOf(cancelled, ConsoleStrings.English.cancelled),
        )
    }
}

/** Matches any of [footers] (`%1$d` standing for the exit code) or of [cancelled]. */
internal fun statusLineRegex(footers: List<String>, cancelled: List<String>): Regex {
    val alternatives = footers.distinct().map { f ->
        f.split("%1\$d").joinToString("""-?\d+""") { Regex.escape(it) }
    } + cancelled.distinct().map { Regex.escape(it) }
    return Regex(alternatives.joinToString("|"))
}

private fun ConsoleLine.toAnnotated(statusLine: Regex): AnnotatedString = buildAnnotatedString {
    val only = pieces.singleOrNull()
    if (only != null && !only.isError && statusLine.matches(only.text)) {
        withStyle(SpanStyle(color = ConsoleDim)) { append(only.text) }
        return@buildAnnotatedString
    }
    for (piece in pieces) {
        if (piece.isError) withStyle(SpanStyle(color = ErrorRed)) { append(piece.text) }
        else append(piece.text)
    }
}

/** Input line drawn as part of the console: a prompt caret, a borderless field, a small Send. */
@Composable
private fun InputRow(enabled: Boolean, autoFocus: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(enabled, autoFocus) { if (enabled && autoFocus) focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(SlateBar)
            .clickable(enabled = enabled, indication = null, interactionSource = null) { focus.requestFocus() }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "\u276F ",
            style = ConsoleTextStyle.copy(color = if (enabled) ConsolePrompt else ConsoleDim),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (!enabled && text.isEmpty()) {
                Text(stringResource(R.string.console_not_running), style = ConsoleTextStyle.copy(color = ConsoleDim))
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                enabled = enabled,
                singleLine = true,
                textStyle = ConsoleTextStyle,
                cursorBrush = SolidColor(ConsolePrompt),
                // Code, not prose: no auto-capitals ("i" → "I") and no autocorrect.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
        IconButton(onClick = send, enabled = enabled) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(R.string.action_send),
                modifier = Modifier.size(18.dp),
                tint = if (enabled) ConsolePrompt else ConsoleDim,
            )
        }
    }
}

@Composable
internal fun Installing() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.console_preparing_python), Modifier.padding(top = 16.dp))
    }
}

@Composable
internal fun InstallError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.console_setup_failed), style = MaterialTheme.typography.titleMedium)
        Text(message, Modifier.padding(vertical = 12.dp), color = ErrorRed)
        Button(onClick = onRetry, colors = brandButtonColors()) { Text(stringResource(R.string.action_retry)) }
    }
}

/** "^C" in a top bar: sends Ctrl+C (SIGINT, KeyboardInterrupt in Python) to what is running. */
@Composable
internal fun InterruptButton(onClick: () -> Unit) {
    val interrupt = stringResource(R.string.action_interrupt)
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = interrupt },
    ) {
        Text("^C", fontFamily = CodeFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/**
 * The console's output and input line without the screen around it, for the debugger panel:
 * the program being debugged prints here and reads its input from here.
 */
@Composable
internal fun EmbeddedConsole(state: ConsoleState, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(ConsoleBackground)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.installError != null -> Text(state.installError, Modifier.padding(12.dp), color = ErrorRed)
                state.installing -> Installing()
                else -> ConsoleOutput(state.lines, state.version, statusLineRegex())
            }
        }
        HorizontalDivider(color = SlateLine)
        InputRow(enabled = state.acceptsInput, autoFocus = false, onSend = onSend)
    }
}
