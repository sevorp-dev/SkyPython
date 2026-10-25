package dev.skyloft.skypython.editor

import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import dev.skyloft.skypython.ui.theme.brandButtonColors
import dev.skyloft.skypython.ui.theme.SuccessGreen
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.Orange
import dev.skyloft.skypython.ui.theme.CodeFont
import dev.skyloft.skypython.runtime.BuildConfig
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.State
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
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
import dev.skyloft.skypython.lang.EditorRange
import dev.skyloft.skypython.lang.LanguageService
import dev.skyloft.skypython.lang.NavTarget
import dev.skyloft.skypython.lang.Navigation
import dev.skyloft.skypython.lang.OutlineItem
import dev.skyloft.skypython.lang.Outlines
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.outlined.CallMade
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.ui.drawer.DrawerAction
import dev.skyloft.skypython.ui.drawer.SkyDrawer
import dev.skyloft.skypython.ui.theme.SlateBar
import dev.skyloft.skypython.ui.theme.BrandText
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.OrangeSoft
import dev.skyloft.skypython.ui.theme.TextBright
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.RunButton
import dev.skyloft.skypython.ui.theme.skyTopBarColors
import dev.skyloft.skypython.ui.theme.Slate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** How long a traceback jump waits for the editor view to show the opened tab. */
private const val JUMP_SETTLE_MILLIS = 150L

private val TabSelected = TextBright
private val TabIdle = MutedText
private val SubtitleColor = MutedText
// Error line: a flat, muted red band (warm, so it sits well beside the orange).
private val ProblemBackground = Color(0xFF3A2629)
private val ProblemRed = Color(0xFFFF7B72)
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

    // ---- code navigation (language server) ----
    val service = remember { LanguageService.get(context) }
    var outlineOpen by remember { mutableStateOf(false) }
    var outlineItems by remember { mutableStateOf<List<OutlineItem>?>(null) }
    // The function / class around the cursor, for the drawer header (refreshed when it opens).
    var drawerSymbol by remember { mutableStateOf<String?>(null) }
    val docPath = (current?.source as? DocSource.Local)?.file?.path
    fun say(id: Int, vararg args: Any) {
        scope.launch { snackbar.showSnackbar(AppLocale.string(context, id, *args)) }
    }
    /** The cursor's position for a lookup, or null (with a message) when navigation can't run. */
    fun navPosition(): LookupPosition? {
        if (!config.codeCompletion) { say(R.string.nav_hints_off); return null }
        val pos = controller.lookupPosition() ?: return null
        val line = pos.source.lineSequence().drop(pos.line - 1).firstOrNull().orEmpty()
        if (wordAt(line, pos.column) == null) { say(R.string.nav_not_on_a_name); return null }
        return pos
    }
    val goToDefinition: () -> Unit = goToDefinition@{
        val pos = navPosition() ?: return@goToDefinition
        val path = docPath
        val name = wordAt(pos.source.lineSequence().drop(pos.line - 1).first(), pos.column).orEmpty()
        scope.launch {
            val defs = service.definition(pos.source, pos.line, pos.column, path)
            when {
                defs == null -> say(R.string.nav_unavailable)
                defs.isEmpty() -> say(R.string.nav_no_definition, name)
                else -> when (val t = Navigation.target(defs.first(), viewModel.projectsRoot)) {
                    null -> say(R.string.nav_no_definition, name)
                    is NavTarget.InFile -> controller.jumpTo(t.range)
                    is NavTarget.OtherFile -> {
                        controller.flushPendingText()
                        controller.hideKeyboard()
                        viewModel.open(t.file, readOnly = t.readOnly) { id -> controller.jumpWhenShown(id, t.range) }
                    }
                }
            }
        }
    }
    val findUsages: () -> Unit = findUsages@{
        val pos = navPosition() ?: return@findUsages
        val name = wordAt(pos.source.lineSequence().drop(pos.line - 1).first(), pos.column).orEmpty()
        scope.launch {
            val usages = service.references(pos.source, pos.line, pos.column, docPath)
            if (usages == null) {
                say(R.string.nav_unavailable)
            } else {
                controller.highlightUsages(usages)
                say(R.string.nav_usages_count, name, usages.size)
            }
        }
    }
    val openOutline: () -> Unit = openOutline@{
        if (current == null) return@openOutline
        if (!config.codeCompletion) { say(R.string.nav_hints_off); return@openOutline }
        val source = controller.lookupPosition()?.source ?: return@openOutline
        controller.hideKeyboard()
        outlineItems = null
        outlineOpen = true
        scope.launch {
            val items = service.outline(source)
            if (items == null) { outlineOpen = false; say(R.string.nav_unavailable) } else outlineItems = items
        }
    }
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) return@LaunchedEffect
        drawerSymbol = null
        val pos = controller.lookupPosition() ?: return@LaunchedEffect
        if (!config.codeCompletion || !service.isReady) return@LaunchedEffect
        val items = service.outline(pos.source) ?: return@LaunchedEffect
        drawerSymbol = Outlines.symbolAt(items, pos.line)
    }
    SideEffect {
        controller.onGoToDefinition = goToDefinition
        controller.onFindUsages = findUsages
    }

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
                EditorShortcut.GoToDefinition -> goToDefinition()
                EditorShortcut.Outline -> openOutline()
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
    if (outlineOpen) {
        OutlineSheet(
            items = outlineItems,
            currentLine = cursor.value.line + 1,
            onPick = { item ->
                outlineOpen = false
                val line = item.line - 1
                controller.jumpTo(EditorRange(line, item.column, line, item.column + item.name.length))
            },
            onDismiss = { outlineOpen = false },
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
            SkyDrawer(
                fileName = current?.name,
                cursor = cursor.value,
                symbol = drawerSymbol,
                onClose = { scope.launch { drawerState.close() } },
            ) { action ->
                scope.launch { drawerState.close() }
                controller.flushPendingText()
                controller.hideKeyboard()
                onDrawerAction(action)
            }
        },
    ) {
        Scaffold(
            containerColor = Slate,
            topBar = {
                Column(Modifier.background(Slate)) {
                    TopAppBar(
                        colors = skyTopBarColors(),
                        navigationIcon = {
                            IconButton(onClick = { controller.hideKeyboard(); scope.launch { drawerState.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.editor_menu))
                            }
                        },
                        title = { EditorTitle(current, viewModel.projectsRoot) },
                        actions = {
                            if (current == null) {
                                IconButton(onClick = openFiles) {
                                    Icon(Icons.Outlined.Folder, contentDescription = stringResource(R.string.title_files))
                                }
                            }
                            // Run lives in the bar (always in the same place, never over the code);
                            // hidden while the debugger panel has its own controls.
                            if (current != null && !current.readOnly && debugPanel == null) {
                                Box(Modifier.padding(horizontal = 4.dp)) {
                                    // Long press: Run / Debug.
                                    RunButton(
                                        onClick = runCurrent,
                                        onLongClick = { runMenuOpen = true },
                                        size = 40.dp,
                                        iconSize = 24.dp,
                                        elevation = 0.dp,
                                    )
                                    DropdownMenu(expanded = runMenuOpen, onDismissRequest = { runMenuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.debug_run_menu_run)) },
                                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                                            onClick = { runMenuOpen = false; runCurrent() },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.debug_run_menu_debug)) },
                                            leadingIcon = { Icon(Icons.Outlined.BugReport, null) },
                                            onClick = { runMenuOpen = false; startCurrent(onDebug) },
                                        )
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.editor_more))
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false; linesMenu = false },
                                ) {
                                    val has = current != null
                                    fun close() { menuOpen = false; linesMenu = false }
                                    if (linesMenu) {
                                        MenuItem(R.string.editor_menu_back, Icons.AutoMirrored.Outlined.ArrowBack) { linesMenu = false }
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
                                    MenuItem(R.string.editor_redo, Icons.AutoMirrored.Outlined.Redo, enabled = has) { close(); controller.redo() }
                                    MenuItem(R.string.find_and_replace, Icons.Outlined.Search, enabled = has) { close(); openFind(false) }
                                    MenuItem(R.string.go_to_line, Icons.Outlined.FormatListNumbered, enabled = has) { close(); goToLineOpen = true }
                                    MenuItem(R.string.editor_action_go_to_definition, Icons.AutoMirrored.Outlined.CallMade, enabled = has) {
                                        close(); goToDefinition()
                                    }
                                    MenuItem(R.string.editor_outline, Icons.Outlined.AccountTree, enabled = has) { close(); openOutline() }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.editor_menu_edit)) },
                                        leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                                        trailingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                                        enabled = has,
                                        onClick = { linesMenu = true },
                                    )
                                    HorizontalDivider()
                                    MenuItem(R.string.new_file, Icons.AutoMirrored.Outlined.NoteAdd) { close(); newFileOpen = true }
                                    MenuItem(R.string.open_file, Icons.Outlined.FileOpen) { close(); openFiles() }
                                    MenuItem(R.string.recent_files, Icons.Outlined.History) { close(); recentOpen = true }
                                    HorizontalDivider()
                                    MenuItem(R.string.editor_save, Icons.Outlined.Save, enabled = has) {
                                        close()
                                        controller.flushPendingText()
                                        viewModel.saveCurrent()
                                    }
                                    MenuItem(R.string.editor_save_as, Icons.Outlined.SaveAs, enabled = has) {
                                        close()
                                        controller.flushPendingText()
                                        current?.let { saveAsLauncher.launch(it.name) }
                                    }
                                }
                            }
                        },
                    )
                    if (current != null) {
                        DocTabs(
                            state,
                            viewModel,
                            onClose = { id -> controller.flushPendingText(); viewModel.close(id) },
                            onNew = { newFileOpen = true },
                        )
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
            bottomBar = {
                // Library code (read-only): nothing to type or fix.
                if (current != null && !current.readOnly) {
                    Column(Modifier.background(SlateBar).navigationBarsPadding().imePadding()) {
                        Hairline()
                        DiagnosticsMapping.firstError(problems)?.let { error ->
                            ProblemLine(error, onClick = { controller.goTo(error) })
                        }
                        StatusLine(cursor, onCursorClick = { goToLineOpen = true })
                        SymbolBar(
                            onInsert = controller::insertSymbol,
                            onAction = onEditAction,
                            keys = symbolKeys,
                            keyboardVisible = imeVisible,
                            onToggleKeyboard = { if (imeVisible) controller.hideKeyboard() else controller.showKeyboard() },
                            onCursorLeft = controller::cursorLeft,
                            onCursorRight = controller::cursorRight,
                            // With the keyboard up the top bar is far from the thumb: a second ▶ here.
                            onRun = if (imeVisible && debugPanel == null) runCurrent else null,
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
                            readOnly = current.readOnly,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                doc.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (doc.readOnly) ReadOnlyBadge(Modifier.padding(start = 8.dp))
        }
        Text(
            docSubtitle(
                doc,
                projectsRoot,
                projectsLabel = stringResource(R.string.projects_label),
                externalLabel = stringResource(R.string.editor_external_document),
            ),
            fontSize = 12.sp,
            color = SubtitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Read-only" on a soft tonal pill: library code opened by "Go to definition". */
@Composable
private fun ReadOnlyBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(colors.primary.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(12.dp))
        Text(
            stringResource(R.string.editor_read_only),
            color = colors.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Tab strip: one chip per open doc with its own ✕ (long press still offers Close), and a + that
 * creates a new file. The current tab sits on a raised slate chip with an orange underline.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocTabs(state: EditorUiState, viewModel: EditorViewModel, onClose: (String) -> Unit, onNew: () -> Unit) {
    val scroll = rememberScrollState()
    // Where each tab starts in the strip, so a newly selected tab scrolls into view.
    val tabX = remember { mutableMapOf<String, Int>() }
    LaunchedEffect(state.currentId) {
        tabX[state.currentId]?.let { x -> scroll.animateScrollTo((x - 48).coerceAtLeast(0)) }
    }
    Row(
        Modifier.fillMaxWidth().background(SlateBar).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(scroll).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.docs.forEach { doc ->
                var menu by remember(doc.id) { mutableStateOf(false) }
                val selected = doc.id == state.currentId
                val label = viewModel.labelFor(doc, state.docs)
                val chip by animateColorAsState(if (selected) Slate else Color.Transparent, label = "tab")
                Box(
                    Modifier
                        .onGloballyPositioned { tabX[doc.id] = it.positionInParent().x.toInt() }
                        .padding(top = 6.dp)
                        .height(38.dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                        .background(chip)
                        .combinedClickable(
                            onClick = { viewModel.select(doc.id) },
                            onLongClick = { menu = true },
                        ),
                ) {
                    Row(
                        Modifier.fillMaxHeight().padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tabTitle(label, doc.dirty),
                            color = if (selected) TabSelected else TabIdle,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                        )
                        val closeLabel = stringResource(R.string.editor_close_tab, label)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = closeLabel) { onClose(doc.id) }
                                .semantics { contentDescription = closeLabel },
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = null,
                                tint = if (selected) MutedText else MutedText.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (selected) {
                        // matchParentSize, not fillMaxWidth: the strip scrolls, so max width is unbounded.
                        Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .padding(horizontal = 10.dp)
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(Orange, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
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
        IconButton(onClick = onNew) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_file), tint = MutedText)
        }
    }
}

/**
 * The quiet line between code and keys: interpreter version on the left, the cursor on the
 * right (tap it: Go to line). Reads [cursor] itself, so moving the cursor recomposes only this.
 */
@Composable
private fun StatusLine(cursor: State<CursorPosition>, onCursorClick: () -> Unit) {
    val format = stringResource(R.string.editor_status_cursor)
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(SuccessGreen, CircleShape))
        Text(
            stringResource(R.string.editor_status_python, BuildConfig.PYTHON_SHORT_VERSION),
            color = MutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        Text(
            cursorLabel(cursor.value, format),
            color = MutedText,
            fontSize = 12.sp,
            fontFamily = CodeFont,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCursorClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** One thin line above the status line: the first error. Tap: jump there and show it. */
@Composable
private fun ProblemLine(error: Diagnostic, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ProblemBackground)
            .clickable(onClickLabel = stringResource(R.string.editor_go_to_error), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
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

/** Nothing open: a calm welcome with the two ways in, and the recent files under them. */
@Composable
private fun EmptyEditor(
    onOpenFiles: () -> Unit,
    onNewFile: () -> Unit,
    recent: List<String>,
    projectsRoot: File,
    onOpenRecent: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.ic_skypython_mark),
            contentDescription = null,
            modifier = Modifier.size(width = 96.dp, height = 67.dp),
        )
        Text(
            stringResource(R.string.editor_no_open_files),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.editor_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MutedText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNewFile, colors = brandButtonColors()) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, null, Modifier.size(18.dp))
                Text(stringResource(R.string.files_new_file), Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onOpenFiles) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                Text(stringResource(R.string.editor_open_files), Modifier.padding(start = 8.dp))
            }
        }
        if (recent.isNotEmpty()) {
            SectionLabel(
                stringResource(R.string.recent_files),
                Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 4.dp),
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
private fun EditAction.menuIcon(): ImageVector = icon ?: Icons.Outlined.EditNote
