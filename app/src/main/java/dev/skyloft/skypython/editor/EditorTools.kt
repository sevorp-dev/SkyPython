package dev.skyloft.skypython.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import kotlinx.coroutines.delay
import java.io.File

/** Search options typed into the bar; also used by tests through [FindQuery]. */
private val CodeKeyboard = KeyboardOptions(
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
    keyboardType = KeyboardType.Ascii,
)

/**
 * Compact find & replace bar shown under the top bar. Typing searches as you go (matches are
 * highlighted in the editor and the first one at the cursor is selected); Enter / ↓ next,
 * Shift+Enter / ↑ previous, Esc or ✕ closes. [showReplace] adds the replace row.
 */
@Composable
fun FindBar(
    controller: EditorController,
    showReplace: Boolean,
    onToggleReplace: () -> Unit,
    onClose: () -> Unit,
    onReplacedAll: (Int) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var replacement by rememberSaveable { mutableStateOf("") }
    var index by remember { mutableIntStateOf(-1) }
    var count by remember { mutableIntStateOf(0) }
    val query = FindQuery(text, matchCase, wholeWord, regex)
    val error = if (regex) query.error else null

    DisposableEffect(controller) {
        controller.onFindState = { i, c -> index = i; count = c }
        onDispose {
            controller.onFindState = null
            controller.stopFind()
        }
    }
    LaunchedEffect(query) {
        delay(120) // let a burst of typing settle before searching the whole file
        controller.find(query)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.Escape -> { onClose(); true }
            Key.Enter, Key.NumPadEnter -> {
                if (e.isShiftPressed) controller.findPrevious() else controller.findNext()
                true
            }
            else -> false
        }
    }

    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                value = text,
                onValueChange = { text = it },
                hint = stringResource(R.string.find_hint),
                isError = error != null,
                imeAction = ImeAction.Search,
                onIme = { controller.findNext() },
                modifier = Modifier.weight(1f).focusRequester(focusRequester).then(keys),
            )
            Text(
                when {
                    error != null -> "!"
                    text.isEmpty() -> ""
                    else -> matchCounter(index, count)
                },
                color = if (error != null || (text.isNotEmpty() && count == 0)) colors.error else colors.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 44.dp).padding(horizontal = 6.dp),
            )
            IconButton(onClick = controller::findPrevious, enabled = count > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.find_previous))
            }
            IconButton(onClick = controller::findNext, enabled = count > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.find_next))
            }
            IconToggleButton(checked = showReplace, onCheckedChange = { onToggleReplace() }) {
                Icon(
                    Icons.Filled.FindReplace,
                    stringResource(R.string.find_toggle_replace),
                    tint = if (showReplace) colors.primary else colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, stringResource(R.string.find_close))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OptionToggle("Aa", stringResource(R.string.find_match_case), matchCase) { matchCase = it }
            OptionToggle("W", stringResource(R.string.find_whole_word), wholeWord) { wholeWord = it }
            OptionToggle(".*", stringResource(R.string.find_regex), regex) { regex = it }
            when {
                error != null -> Text(
                    stringResource(R.string.find_invalid_regex) + ": " + error,
                    color = colors.error, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                text.isNotEmpty() && count == 0 -> Text(
                    stringResource(R.string.find_no_matches),
                    color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
        if (showReplace) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    hint = stringResource(R.string.replace_hint),
                    isError = false,
                    imeAction = ImeAction.Done,
                    onIme = { controller.replaceCurrent(replacement) },
                    modifier = Modifier.weight(1f).then(keys),
                )
                TextButton(onClick = { controller.replaceCurrent(replacement) }, enabled = count > 0) {
                    Text(stringResource(R.string.find_replace_one))
                }
                TextButton(onClick = { onReplacedAll(controller.replaceAll(replacement)) }, enabled = count > 0) {
                    Text(stringResource(R.string.find_replace_all))
                }
            }
        }
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isError: Boolean,
    imeAction: ImeAction,
    onIme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontFamily = FontFamily.Monospace, fontSize = 15.sp),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = CodeKeyboard.copy(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        modifier = modifier.semantics { contentDescription = hint },
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                    .then(
                        if (isError) Modifier.background(colors.error.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        else Modifier,
                    )
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(hint, color = colors.onSurfaceVariant, fontSize = 15.sp)
                inner()
            }
        },
    )
}

@Composable
private fun OptionToggle(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 44.dp, height = 32.dp)
            .background(
                if (checked) colors.primary.copy(alpha = 0.25f) else colors.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp),
            )
            .clickable { onChange(!checked) }
            .semantics { contentDescription = description },
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = if (checked) colors.primary else colors.onSurfaceVariant,
        )
    }
}

/** Asks for a line number (numeric keyboard) and jumps there. */
@Composable
fun GoToLineDialog(lineCount: Int, onGo: (line: Int) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val target = parseGoToLine(input, lineCount)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_line_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { v -> input = v.filter(Char::isDigit).take(9) },
                label = { Text(stringResource(R.string.go_to_line_label, lineCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { target?.let(onGo) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { target?.let(onGo) }, enabled = target != null) {
                Text(stringResource(R.string.go_to_line_go))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Name for a new file in Projects; [onCreate] reports an error message or null when done. */
@Composable
fun NewFileDialog(onCreate: (name: String, onResult: (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("untitled.py", TextRange(0, "untitled".length))) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val create = {
        onCreate(value.text) { e -> if (e == null) onDismiss() else error = e }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_file_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; error = null },
                label = { Text(stringResource(R.string.new_file_name)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = CodeKeyboard.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = create, enabled = value.text.isNotBlank()) { Text(stringResource(R.string.new_file_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The last opened files; tap one to open it. */
@Composable
fun RecentFilesDialog(paths: List<String>, projectsRoot: File, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recent_files)) },
        text = { RecentFilesList(paths, projectsRoot, onOpen) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** File name over its folder (relative to Projects), one row per recent file. */
@Composable
fun RecentFilesList(paths: List<String>, projectsRoot: File, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (paths.isEmpty()) {
        Text(stringResource(R.string.recent_files_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
        return
    }
    LazyColumn(modifier) {
        items(paths, key = { it }) { path ->
            val file = File(path)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        recentFolderLabel(file, projectsRoot, stringResource(R.string.projects_label)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "Projects/demo" for files under the projects root, else the folder path. */
fun recentFolderLabel(file: File, projectsRoot: File, projectsLabel: String = PROJECTS_LABEL): String {
    val folder = file.parentFile ?: return file.path
    val relative = folder.relativeToOrNull(projectsRoot) ?: return folder.path
    return when {
        relative.path.startsWith("..") -> folder.path
        relative.path.isEmpty() -> projectsLabel
        else -> projectsLabel + "/" + relative.invariantSeparatorsPath
    }
}
