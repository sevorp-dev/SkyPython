package dev.skyloft.skypython.debug

import dev.skyloft.skypython.ui.theme.CodeFont
import dev.skyloft.skypython.ui.theme.SlateBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.console.ConsoleState
import dev.skyloft.skypython.console.EmbeddedConsole
import java.io.File

/** Test tags of the panel, for device tests. */
object DebugTags {
    const val PANEL = "debug-panel"
    const val STATUS = "debug-status"
    const val VARIABLE = "debug-variable"
}

private enum class DebugTab(val label: Int) {
    Variables(R.string.debug_tab_variables),
    Watches(R.string.debug_tab_watches),
    Stack(R.string.debug_tab_stack),
    Console(R.string.debug_tab_console),
}

/**
 * The debugger's bottom panel: status and controls always; below them, unless collapsed, the
 * Variables / Watches / Call stack / Console tabs. Shown from a debug start until closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPanel(
    state: DebugState,
    console: ConsoleState,
    watches: List<String>,
    onStep: (Step) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onSelectFrame: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    onAddWatch: (String) -> Unit,
    onRemoveWatch: (Int) -> Unit,
    onSendInput: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(DebugTab.Variables.ordinal) }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = SlateBar,
        contentColor = colors.onSurface,
        modifier = modifier.fillMaxWidth().testTag(DebugTags.PANEL),
    ) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Controls(state, expanded, onToggleExpanded = { expanded = !expanded }, onStep, onPause, onStop, onClose)
            StatusLine(state)
            if (state.phase == DebugPhase.Starting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (expanded) {
                val height = (LocalConfiguration.current.screenHeightDp * 0.38f).dp.coerceIn(160.dp, 420.dp)
                PrimaryScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 0.dp,
                    containerColor = SlateBar,
                    contentColor = colors.primary,
                ) {
                    DebugTab.entries.forEach { t ->
                        Tab(
                            selected = tab == t.ordinal,
                            onClick = { tab = t.ordinal },
                            text = { Text(stringResource(t.label), maxLines = 1) },
                            unselectedContentColor = colors.onSurfaceVariant,
                        )
                    }
                }
                // Shrinks when space is short (keyboard up), so the console's input line stays visible.
                Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = height)) {
                    when (DebugTab.entries[tab]) {
                        DebugTab.Variables -> VariablesTab(state, onToggle)
                        DebugTab.Watches -> WatchesTab(state, watches, onToggle, onAddWatch, onRemoveWatch)
                        DebugTab.Stack -> StackTab(state, onSelectFrame)
                        DebugTab.Console -> EmbeddedConsole(console, onSendInput, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    state: DebugState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onStep: (Step) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val paused = state.phase == DebugPhase.Paused
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.phase == DebugPhase.Running) {
            ControlButton(Icons.Outlined.Pause, R.string.debug_pause, enabled = true, onClick = onPause)
        } else {
            ControlButton(Icons.Filled.PlayArrow, R.string.debug_continue, enabled = paused) { onStep(Step.Continue) }
        }
        ControlButton(Icons.AutoMirrored.Outlined.Redo, R.string.debug_step_over, enabled = paused) { onStep(Step.Over) }
        ControlButton(Icons.Outlined.ArrowDownward, R.string.debug_step_into, enabled = paused) { onStep(Step.Into) }
        ControlButton(Icons.Outlined.ArrowUpward, R.string.debug_step_out, enabled = paused) { onStep(Step.Out) }
        if (state.isLive) {
            IconButton(onClick = onStop) {
                Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.action_stop), tint = colors.error)
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleExpanded) {
            Icon(
                if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = stringResource(if (expanded) R.string.debug_collapse else R.string.debug_expand),
            )
        }
        if (!state.isLive) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.debug_close))
            }
        }
    }
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** What the program is doing, in one line: where it stopped, or how it ended. */
@Composable
private fun StatusLine(state: DebugState) {
    val colors = MaterialTheme.colorScheme
    val frame = state.currentFrame
    val file = frame?.file?.let { File(it).name }.orEmpty()
    val text = when (state.phase) {
        DebugPhase.Idle -> ""
        DebugPhase.Starting -> stringResource(R.string.debug_status_starting)
        DebugPhase.Running -> stringResource(R.string.debug_status_running)
        DebugPhase.Paused -> when {
            state.reason == StopReason.EXCEPTION -> stringResource(R.string.debug_status_exception, state.exception.orEmpty())
            state.reason == StopReason.BREAKPOINT && frame != null -> stringResource(R.string.debug_status_breakpoint, file, frame.line)
            frame != null -> stringResource(R.string.debug_status_paused, file, frame.line)
            else -> ""
        }
        DebugPhase.Ended -> when {
            state.error != null -> state.error
            state.exitCode != null -> stringResource(R.string.debug_status_finished, state.exitCode)
            else -> stringResource(R.string.debug_status_ended)
        }
    }
    val isError = state.error != null || state.reason == StopReason.EXCEPTION
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.BugReport,
            contentDescription = null,
            tint = if (isError) colors.error else colors.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (isError) colors.error else colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).testTag(DebugTags.STATUS),
        )
    }
}

/** A hint in the middle of an empty tab. */
@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VariablesTab(state: DebugState, onToggle: (Int) -> Unit) {
    if (state.phase != DebugPhase.Paused) {
        Hint(stringResource(R.string.debug_variables_when_paused))
        return
    }
    val localsTitle = stringResource(R.string.debug_locals)
    val globalsTitle = stringResource(R.string.debug_globals)
    LazyColumn(Modifier.fillMaxSize()) {
        state.locals?.let { page -> scope(localsTitle, page, state, onToggle) }
        state.globals?.let { page -> scope(globalsTitle, page, state, onToggle) }
    }
}

private fun LazyListScope.scope(title: String, page: VariablesPage, state: DebugState, onToggle: (Int) -> Unit) {
    item(key = "h-$title") { SectionHeader(title) }
    if (page.variables.isEmpty()) {
        item(key = "e-$title") { EmptyRow() }
    }
    tree(page, depth = 0, state, onToggle, keyPrefix = title)
}

/** [page] and, below each expanded container, its children (indented). */
private fun LazyListScope.tree(page: VariablesPage, depth: Int, state: DebugState, onToggle: (Int) -> Unit, keyPrefix: String) {
    page.variables.forEachIndexed { i, v ->
        val key = "$keyPrefix/$i"
        item(key = key) { VariableRow(v, depth, expanded = v.ref in state.expanded, onToggle) }
        if (v.ref > 0 && v.ref in state.expanded) {
            val children = state.children[v.ref]
            if (children != null) tree(children, depth + 1, state, onToggle, key)
        }
    }
    if (page.more > 0) {
        item(key = "$keyPrefix/more") { MoreRow(page.more, depth) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.2.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyRow() {
    Text(
        stringResource(R.string.debug_no_variables),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 36.dp, top = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun MoreRow(more: Int, depth: Int) {
    Text(
        stringResource(R.string.debug_more, more),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = (36 + depth * 16).dp, top = 2.dp, bottom = 6.dp),
    )
}

/** `name = value  type`, with a chevron when it can be expanded. */
@Composable
private fun VariableRow(v: Variable, depth: Int, expanded: Boolean, onToggle: (Int) -> Unit, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .then(if (v.ref > 0) Modifier.clickable(role = Role.Button) { onToggle(v.ref) } else Modifier)
            .padding(start = (8 + depth * 16).dp, end = 12.dp)
            .testTag(DebugTags.VARIABLE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (v.ref > 0) {
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        val code = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont)
        if (v.name.isNotEmpty()) {
            Text(v.name, style = code, color = colors.primary, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(" = ", style = code, color = colors.onSurfaceVariant)
        }
        Text(
            v.value,
            style = code,
            color = if (error) colors.error else colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (v.type.isNotEmpty()) {
            Text(
                "  " + (v.size?.let { "${v.type}[$it]" } ?: v.type),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WatchesTab(
    state: DebugState,
    watches: List<String>,
    onToggle: (Int) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (watches.isEmpty()) {
                item { Hint(stringResource(R.string.debug_no_watches)) }
            }
            itemsIndexed(watches, key = { i, e -> "$i:$e" }) { i, expression ->
                val result = state.watches.firstOrNull { it.expression == expression }?.result
                WatchRow(expression, result, state, onToggle, onRemove = { onRemove(i) })
            }
        }
        AddWatchRow(onAdd)
    }
}

@Composable
private fun WatchRow(expression: String, result: EvalResult?, state: DebugState, onToggle: (Int) -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                val shown = when (result) {
                    is EvalResult.Value -> result.variable.copy(name = expression)
                    is EvalResult.Error -> Variable(expression, result.message, "", 0, null)
                    null -> Variable(expression, if (state.phase == DebugPhase.Paused) "…" else "—", "", 0, null)
                }
                VariableRow(shown, 0, expanded = shown.ref in state.expanded, onToggle, error = result is EvalResult.Error)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.debug_watch_remove), tint = colors.onSurfaceVariant)
            }
        }
        val ref = (result as? EvalResult.Value)?.variable?.ref ?: 0
        if (ref > 0 && ref in state.expanded) {
            state.children[ref]?.let { page ->
                page.variables.forEach { child -> ChildRows(child, 1, state, onToggle) }
                if (page.more > 0) MoreRow(page.more, 1)
            }
        }
    }
}

/** Expanded watch values: rows outside a LazyColumn, so a bounded recursion of plain rows. */
@Composable
private fun ChildRows(v: Variable, depth: Int, state: DebugState, onToggle: (Int) -> Unit) {
    VariableRow(v, depth, expanded = v.ref in state.expanded, onToggle)
    if (v.ref > 0 && v.ref in state.expanded && depth < MAX_WATCH_DEPTH) {
        state.children[v.ref]?.let { page ->
            page.variables.forEach { ChildRows(it, depth + 1, state, onToggle) }
            if (page.more > 0) MoreRow(page.more, depth + 1)
        }
    }
}

private const val MAX_WATCH_DEPTH = 8

@Composable
private fun AddWatchRow(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        if (text.isNotBlank()) onAdd(text)
        text = ""
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.debug_watch_hint), maxLines = 1) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = add, enabled = text.isNotBlank()) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.debug_watch_add))
        }
    }
}

@Composable
private fun StackTab(state: DebugState, onSelect: (Int) -> Unit) {
    if (state.phase != DebugPhase.Paused) {
        Hint(stringResource(R.string.debug_stack_when_paused))
        return
    }
    val colors = MaterialTheme.colorScheme
    val library = stringResource(R.string.debug_frame_library)
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.frames, key = { it.id }) { frame ->
            val selected = frame.id == state.selectedFrame
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) colors.primaryContainer else colors.surfaceContainer)
                    .clickable(role = Role.Button) { onSelect(frame.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    frame.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (frame.user) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${File(frame.file).name}:${frame.line}" + if (frame.user) "" else " · $library",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
