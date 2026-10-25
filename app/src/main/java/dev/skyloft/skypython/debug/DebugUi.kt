package dev.skyloft.skypython.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.R
import dev.skyloft.skypython.console.ConsoleViewModel
import dev.skyloft.skypython.editor.EditorDebugMarks

/**
 * The editor marks for the document at [docPath] (null: not a file in app storage, where
 * breakpoints aren't supported and a tap calls [onUnsupported]). A long press on the gutter
 * calls [onEditCondition].
 */
@Composable
fun rememberDebugMarks(
    vm: DebugViewModel,
    docPath: String?,
    onEditCondition: (path: String, line: Int) -> Unit,
    onUnsupported: () -> Unit,
): EditorDebugMarks {
    val all by vm.store.state.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val list = docPath?.let { all[it] }.orEmpty()
    val frame = st.currentFrame
    val executionLine = frame?.line?.takeIf { st.phase == DebugPhase.Paused && frame.file == docPath }
    val breakpointColor = colors.error.toArgb()
    val executionColor = colors.secondary.toArgb()
    return remember(docPath, list, executionLine, breakpointColor, executionColor) {
        EditorDebugMarks(
            breakpoints = list.mapTo(HashSet()) { it.line },
            conditional = list.filter { it.condition != null }.mapTo(HashSet()) { it.line },
            executionLine = executionLine,
            breakpointColor = breakpointColor,
            executionColor = executionColor,
            onGutterTap = { line -> if (docPath != null) vm.toggleBreakpoint(docPath, line) else onUnsupported() },
            onGutterLongPress = { line -> if (docPath != null) onEditCondition(docPath, line) else onUnsupported() },
            onLinesEdited = docPath?.let { path -> { edit -> vm.onLinesEdited(path, edit) } },
        )
    }
}

/** Edits the condition of the breakpoint on [line] of [path] (creating it on save). */
@Composable
fun BreakpointConditionDialog(vm: DebugViewModel, path: String, line: Int, onDismiss: () -> Unit) {
    val all by vm.store.state.collectAsStateWithLifecycle()
    val existing = all[path]?.firstOrNull { it.line == line }
    var text by remember(path, line) { mutableStateOf(existing?.condition.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_condition_title, line)) },
        text = {
            Column {
                Text(stringResource(R.string.debug_condition_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.debug_condition_label)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setCondition(path, line, text)
                onDismiss()
            }) { Text(stringResource(R.string.debug_condition_save)) }
        },
        dismissButton = {
            if (existing != null) {
                TextButton(onClick = {
                    vm.toggleBreakpoint(path, line)
                    onDismiss()
                }) { Text(stringResource(R.string.debug_condition_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** [DebugPanel] wired to the view models; nothing while no session is shown. */
@Composable
fun DebugPanelHost(vm: DebugViewModel, consoleVm: ConsoleViewModel, modifier: Modifier = Modifier) {
    val st by vm.state.collectAsStateWithLifecycle()
    if (st.phase == DebugPhase.Idle) return
    val console by consoleVm.state.collectAsStateWithLifecycle()
    val watches by vm.store.watches.collectAsStateWithLifecycle()
    DebugPanel(
        state = st,
        console = console,
        watches = watches,
        onStep = vm::resume,
        onPause = vm::pause,
        onStop = { vm.stop(consoleVm::stop) },
        onClose = vm::close,
        onSelectFrame = vm::selectFrame,
        onToggle = vm::toggleExpanded,
        onAddWatch = vm::addWatch,
        onRemoveWatch = vm::removeWatch,
        onSendInput = consoleVm::send,
        modifier = modifier,
    )
}
