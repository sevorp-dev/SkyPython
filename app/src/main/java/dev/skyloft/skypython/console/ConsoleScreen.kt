package dev.skyloft.skypython.console

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.ui.theme.DividerNavy
import dev.skyloft.skypython.ui.theme.EditorBackground
import dev.skyloft.skypython.ui.theme.ErrorRed
import androidx.compose.material3.HorizontalDivider
import dev.skyloft.skypython.ui.theme.NightNavy
import dev.skyloft.skypython.ui.theme.SkyCyan
import dev.skyloft.skypython.ui.theme.brandButtonColors
import dev.skyloft.skypython.ui.theme.SkyTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/** Terminal-like colours for the output area, independent of the app theme. */
private val ConsoleBackground = NightNavy
private val ConsoleText = Color(0xFFDCE3F5)
private val ConsoleDim = Color(0xFF7480AB)
private val ConsolePrompt = SkyCyan

private val ConsoleTextStyle = TextStyle(
    color = ConsoleText,
    fontFamily = FontFamily.Monospace,
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
                title = state.title ?: "Console",
                onBack = onBack,
                actions = {
                    if (state.running) {
                        IconButton(onClick = viewModel::stop) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = ErrorRed)
                        }
                    } else {
                        IconButton(onClick = viewModel::restart) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Restart")
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
                    else -> ConsoleOutput(state.lines, state.version)
                }
            }
            HorizontalDivider(color = DividerNavy)
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
private fun ConsoleOutput(lines: List<ConsoleLine>, version: Long) {
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
                Text(text = remember(line) { line.toAnnotated() }, style = ConsoleTextStyle)
            }
        }
    }
}

/** The console's own status lines (exit footer, "[Cancelled]"), dimmed so program output stands out. */
private val STATUS_LINE = Regex("""\[(Program finished, exit code -?\d+|Cancelled)]""")

private fun ConsoleLine.toAnnotated(): AnnotatedString = buildAnnotatedString {
    val only = pieces.singleOrNull()
    if (only != null && !only.isError && STATUS_LINE.matches(only.text)) {
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
            .background(EditorBackground)
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
                Text("Not running", style = ConsoleTextStyle.copy(color = ConsoleDim))
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
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                modifier = Modifier.size(18.dp),
                tint = if (enabled) ConsolePrompt else ConsoleDim,
            )
        }
    }
}

@Composable
private fun Installing() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Preparing Python…", Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun InstallError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Could not set up Python", style = MaterialTheme.typography.titleMedium)
        Text(message, Modifier.padding(vertical = 12.dp), color = ErrorRed)
        Button(onClick = onRetry, colors = brandButtonColors()) { Text("Retry") }
    }
}
