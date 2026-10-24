package dev.skyloft.skypython.console

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.ui.theme.ErrorRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(viewModel: ConsoleViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.scriptName ?: "Console") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.installError != null -> InstallError(state.installError!!, viewModel::retryInstall)
                    state.installing -> Installing()
                    else -> ConsoleOutput(state.lines, state.version)
                }
            }
            InputRow(enabled = state.running, onSend = viewModel::send)
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
                Text(
                    text = remember(line) { line.toAnnotated() },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

private fun ConsoleLine.toAnnotated(): AnnotatedString = buildAnnotatedString {
    for (piece in pieces) {
        if (piece.isError) withStyle(SpanStyle(color = ErrorRed)) { append(piece.text) }
        else append(piece.text)
    }
}

@Composable
private fun InputRow(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(if (enabled) "Input" else "Program is not running") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(onClick = send, enabled = enabled) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
        Button(onClick = onRetry) { Text("Retry") }
    }
}
