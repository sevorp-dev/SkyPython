package dev.skyloft.skypython.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.settings.parseSymbolKeys
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.lang.Diagnostic
import dev.skyloft.skypython.lang.DiagnosticsMapping
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.ui.drawer.DrawerAction
import dev.skyloft.skypython.ui.drawer.SkyDrawer
import dev.skyloft.skypython.ui.theme.BarNavy
import dev.skyloft.skypython.ui.theme.BrandText
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.SkyBlue
import dev.skyloft.skypython.ui.theme.TextBright
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.RunButton
import dev.skyloft.skypython.ui.theme.skyTopBarColors
import dev.skyloft.skypython.ui.theme.EditorBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** How long a traceback jump waits for the editor view to show the opened tab. */
private const val JUMP_SETTLE_MILLIS = 150L

private val TabSelected = TextBright
private val TabIdle = MutedText
private val SubtitleColor = MutedText
// Error status line: a flat, muted red band.
private val ProblemBackground = Color(0xFF2A1A26)
private val ProblemRed = Color(0xFFFF7A8A)
private val ProblemText = Color(0xFFFFD9DE)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    settings: EditorSettings,
    onRun: (File) -> Unit,
    onOpenFiles: () -> Unit,
    onDrawerAction: (DrawerAction) -> Unit,
    /** Debug the saved current file (long press on ▶, or the drawer). */
    onDebug: (File) -> Unit = {},
    debugMarks: EditorDebugMarks = EditorDebugMarks.None,
    /** The debugger panel, shown under the code while a debug session is open (▶ is hidden then). */
    debugPanel: (@Composable () -> Unit)? = null,
    /** The user pinch-zoomed the editor to this font size (sp); persist it. */
    onFontSizeChange: (Int) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = remember { EditorController() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var menuOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    // Read only inside the drawer, so cursor moves don't recompose the whole screen.
    val cursor = remember { mutableStateOf(CursorPosition()) }
    // Problems the language server found in the shown doc (squiggles; the first error is listed).
    var problems by remember { mutableStateOf(emptyList<Diagnostic>()) }
    val config = remember(settings) { EditorViewConfig.from(settings) }
    val symbolKeys = remember(settings.symbolKeys) { parseSymbolKeys(settings.symbolKeys) }
    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Overflow menu page: the main list or the "Edit lines" sub-list.
    var linesMenu by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    val findFocus = remember { FocusRequester() }
    var goToLineOpen by remember { mutableStateOf(false) }
    var newFileOpen by remember { mutableStateOf(false) }
    var recentOpen by remember { mutableStateOf(false) }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-python"),
    ) { uri -> uri?.let(viewModel::saveAs) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    // A tapped traceback line: once its file is the shown tab, put the cursor on that line.
    val jump by viewModel.jump.collectAsStateWithLifecycle()
    LaunchedEffect(jump, state.currentId) {
        val target = jump ?: return@LaunchedEffect
        val shown = (state.current?.source as? DocSource.Local)?.file ?: return@LaunchedEffect
        if (shown.canonicalPath != target.file.canonicalPath) return@LaunchedEffect
        delay(JUMP_SETTLE_MILLIS) // the editor view takes the new tab's text first
        controller.goToLine(target.line - 1) // the controller counts from 0
        viewModel.jumpDone()
    }
    // ON_PAUSE always precedes finishing/backgrounding; ON_STOP is kept as a cheap second chance
    // (it is a no-op when nothing is dirty). Saves are NonCancellable, see DocumentSaver.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { controller.flushPendingText(); viewModel.saveAll() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.flushPendingText(); viewModel.saveAll() }
    // Edits reach the ViewModel debounced; flush before anything that reads the text.
    val openFiles = { controller.flushPendingText(); controller.hideKeyboard(); onOpenFiles() }

    val current = state.current
    val imeVisible = WindowInsets.isImeVisible
    val startCurrent: ((File) -> Unit) -> Unit = { start ->
        if (!running) {
            running = true
            controller.flushPendingText()
            controller.hideKeyboard()
            scope.launch {
                try {
                    viewModel.runCurrent()?.let(start)
                } finally {
                    running = false
                }
            }
        }
    }
    val runCurrent: () -> Unit = { startCurrent(onRun) }
    var runMenuOpen by remember { mutableStateOf(false) }

    val openFind = { replace: Boolean ->
        if (findOpen) runCatching { findFocus.requestFocus() }
        findOpen = true
        if (replace) showReplace = true
    }
    val onEditAction: (EditAction) -> Unit = { action ->
        when (action) {
            EditAction.Undo -> controller.undo()
            EditAction.Redo -> controller.redo()
            EditAction.Comment -> controller.toggleComment()
            EditAction.Indent -> controller.indent()
            EditAction.Outdent -> controller.outdent()
            EditAction.MoveUp -> controller.moveLineUp()
            EditAction.MoveDown -> controller.moveLineDown()
            EditAction.Duplicate -> controller.duplicateLine()
            EditAction.DeleteLine -> controller.deleteLine()
            EditAction.Find -> openFind(false)
            EditAction.GoToLine -> goToLineOpen = true
        }
    }
    // Hardware keyboard shortcuts and pinch-zoom come from the editor view.
    SideEffect {
        controller.onShortcut = { shortcut ->
            when (shortcut) {
                EditorShortcut.Save -> { controller.flushPendingText(); viewModel.saveCurrent() }
                EditorShortcut.Find -> openFind(false)
                EditorShortcut.Replace -> openFind(true)
                EditorShortcut.GoToLine -> goToLineOpen = true
                EditorShortcut.ToggleComment -> controller.toggleComment()
                EditorShortcut.Run -> runCurrent()
                EditorShortcut.Redo -> controller.redo()
            }
        }
        controller.onPinchFontSize = onFontSizeChange
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = findOpen && !drawerState.isOpen) { findOpen = false }

    if (goToLineOpen) {
        GoToLineDialog(
            lineCount = controller.lineCount(),
            onGo = { line -> goToLineOpen = false; controller.goToLine(line) },
            onDismiss = { goToLineOpen = false },
        )
    }
    if (newFileOpen) {
        NewFileDialog(onCreate = viewModel::newFile, onDismiss = { newFileOpen = false })
    }
    if (recentOpen) {
        RecentFilesDialog(
            paths = recentFiles,
            projectsRoot = viewModel.projectsRoot,
            onOpen = { path -> recentOpen = false; viewModel.openRecent(path) },
            onDismiss = { recentOpen = false },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Opened by ☰ only: an edge swipe would fight horizontal scrolling in the code view.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            SkyDrawer(fileName = current?.name, cursor = cursor.value) { action ->
                scope.launch { drawerState.close() }
                controller.flushPendingText()
                controller.hideKeyboard()
                onDrawerAction(action)
            }
        },
    ) {
        Scaffold(
            containerColor = EditorBackground,
            topBar = {
                Column(Modifier.background(BarNavy)) {
                    TopAppBar(
                        colors = skyTopBarColors(),
                        navigationIcon = {
                            IconButton(onClick = { controller.hideKeyboard(); scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.editor_menu))
                            }
                        },
                        title = { EditorTitle(current, viewModel.projectsRoot) },
                        actions = {
                            IconButton(onClick = openFiles) {
                                Icon(Icons.Outlined.Folder, contentDescription = stringResource(R.string.title_files))
                            }
                            IconButton(onClick = controller::undo, enabled = current != null) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.editor_undo))
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.editor_more))
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false; linesMenu = false },
                                ) {
                                    val has = current != null
                                    fun close() { menuOpen = false; linesMenu = false }
                                    if (linesMenu) {
                                        MenuItem(R.string.editor_menu_back, Icons.AutoMirrored.Filled.ArrowBack) { linesMenu = false }
                                        HorizontalDivider()
                                        listOf(
                                            EditAction.Comment, EditAction.Indent, EditAction.Outdent, EditAction.Duplicate,
                                            EditAction.DeleteLine, EditAction.MoveUp, EditAction.MoveDown,
                                        ).forEach { action ->
                                            MenuItem(action.description, action.menuIcon(), enabled = has) {
                                                close(); onEditAction(action)
                                            }
                                        }
                                        return@DropdownMenu
                                    }
                                    MenuItem(R.string.editor_redo, Icons.AutoMirrored.Filled.Redo, enabled = has) { close(); controller.redo() }
                                    MenuItem(R.string.find_and_replace, Icons.Filled.Search, enabled = has) { close(); openFind(false) }
                                    MenuItem(R.string.go_to_line, Icons.Filled.FormatListNumbered, enabled = has) { close(); goToLineOpen = true }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.editor_menu_edit)) },
                                        leadingIcon = { Icon(Icons.Filled.EditNote, null) },
                                        trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                                        enabled = has,
                                        onClick = { linesMenu = true },
                                    )
                                    HorizontalDivider()
                                    MenuItem(R.string.new_file, Icons.AutoMirrored.Filled.NoteAdd) { close(); newFileOpen = true }
                                    MenuItem(R.string.open_file, Icons.Filled.FileOpen) { close(); openFiles() }
                                    MenuItem(R.string.recent_files, Icons.Filled.History) { close(); recentOpen = true }
                                    HorizontalDivider()
                                    MenuItem(R.string.editor_save, Icons.Filled.Save, enabled = has) {
                                        close()
                                        controller.flushPendingText()
                                        viewModel.saveCurrent()
                                    }
                                    MenuItem(R.string.editor_save_as, Icons.Filled.SaveAs, enabled = has) {
                                        close()
                                        controller.flushPendingText()
                                        current?.let { saveAsLauncher.launch(it.name) }
                                    }
                                }
                            }
                        },
                    )
                    if (current != null) {
                        DocTabs(state, viewModel, onClose = { id -> controller.flushPendingText(); viewModel.close(id) })
                    }
                    Hairline()
                    if (findOpen && current != null) {
                        FindBar(
                            controller = controller,
                            showReplace = showReplace,
                            onToggleReplace = { showReplace = !showReplace },
                            onClose = { findOpen = false },
                            onReplacedAll = { n ->
                                scope.launch {
                                    snackbar.showSnackbar(
                                        if (n > 0) AppLocale.string(context, R.string.find_replaced_count, n)
                                        else AppLocale.string(context, R.string.find_no_matches),
                                    )
                                }
                            },
                            focusRequester = findFocus,
                        )
                        Hairline()
                    }
                }
            },
            floatingActionButton = {
                // With the keyboard up the FAB would sit on the lines being typed; Run moves
                // into the symbol bar instead.
                if (current != null && !imeVisible && debugPanel == null) {
                    Box {
                        // Long press: Run / Debug.
                        RunButton(onClick = runCurrent, onLongClick = { runMenuOpen = true })
                        DropdownMenu(expanded = runMenuOpen, onDismissRequest = { runMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.debug_run_menu_run)) },
                                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                                onClick = { runMenuOpen = false; runCurrent() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.debug_run_menu_debug)) },
                                leadingIcon = { Icon(Icons.Filled.BugReport, null) },
                                onClick = { runMenuOpen = false; startCurrent(onDebug) },
                            )
                        }
                    }
                }
            },
            bottomBar = {
                if (current != null) {
                    Column(Modifier.navigationBarsPadding().imePadding()) {
                        DiagnosticsMapping.firstError(problems)?.let { error ->
                            ProblemLine(error, onClick = { controller.goTo(error) })
                        }
                        SymbolBar(
                            onInsert = controller::insertSymbol,
                            onAction = onEditAction,
                            keys = symbolKeys,
                            onRun = if (imeVisible) runCurrent else null,
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        current == null -> EmptyEditor(
                            onOpenFiles = openFiles,
                            onNewFile = { newFileOpen = true },
                            recent = recentFiles,
                            projectsRoot = viewModel.projectsRoot,
                            onOpenRecent = viewModel::openRecent,
                        )
                        else -> CodeEditor(
                            docId = current.id,
                            docPath = (current.source as? DocSource.Local)?.file?.path,
                            initialText = current.text,
                            openIds = state.docs.mapTo(HashSet()) { it.id },
                            controller = controller,
                            config = config,
                            onTextChanged = viewModel::onTextChanged,
                            onCursorChanged = { cursor.value = it },
                            onDiagnostics = { problems = it },
                            modifier = Modifier.fillMaxSize(),
                            debugMarks = debugMarks,
                        )
                    }
                }
                debugPanel?.invoke()
            }
        }
    }
}

/** File name (bold) over its folder path, both ellipsized. */
@Composable
private fun EditorTitle(doc: OpenDocument?, projectsRoot: File) {
    if (doc == null) {
        BrandText(stringResource(R.string.app_name), fontSize = 20.sp)
        return
    }
    Column {
        Text(
            doc.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            docSubtitle(
                doc,
                projectsRoot,
                projectsLabel = stringResource(R.string.projects_label),
                externalLabel = stringResource(R.string.editor_external_document),
            ),
            fontSize = 13.sp,
            color = SubtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact text-only tab strip; long-press a tab to close it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocTabs(state: EditorUiState, viewModel: EditorViewModel, onClose: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.docs.forEach { doc ->
            var menu by remember(doc.id) { mutableStateOf(false) }
            val selected = doc.id == state.currentId
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(40.dp)
                    .combinedClickable(
                        onClick = { viewModel.select(doc.id) },
                        onLongClick = { menu = true },
                    )
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    tabTitle(viewModel.labelFor(doc, state.docs), doc.dirty),
                    color = if (selected) TabSelected else TabIdle,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
                // A thin marker, so the current tab is clear even with many similar names.
                if (selected && state.docs.size > 1) {
                    // matchParentSize, not fillMaxWidth: the strip scrolls, so max width is unbounded.
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(SkyBlue, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                        )
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_close)) }, onClick = {
                        menu = false
                        onClose(doc.id)
                    })
                }
            }
        }
    }
}

/** One thin line above the symbol bar: the first error. Tap: jump there and show it. */
@Composable
private fun ProblemLine(error: Diagnostic, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ProblemBackground)
            .clickable(onClickLabel = "Go to error", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = ProblemRed,
            modifier = Modifier.size(16.dp),
        )
        Text(
            problemLabel(error),
            color = ProblemText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun EmptyEditor(
    onOpenFiles: () -> Unit,
    onNewFile: () -> Unit,
    recent: List<String>,
    projectsRoot: File,
    onOpenRecent: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.editor_no_open_files), style = MaterialTheme.typography.titleMedium)
        Row {
            TextButton(onClick = onNewFile) { Text(stringResource(R.string.new_file)) }
            TextButton(onClick = onOpenFiles) { Text(stringResource(R.string.editor_open_files)) }
        }
        if (recent.isNotEmpty()) {
            Text(
                stringResource(R.string.recent_files),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
            )
            RecentFilesList(recent, projectsRoot, onOpenRecent, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MenuItem(label: Int, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Menu icon for a line command (the bar shows `#` as text for Comment). */
private fun EditAction.menuIcon(): ImageVector = icon ?: Icons.Filled.EditNote
