package dev.skyloft.skypython.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.ui.drawer.DrawerAction
import dev.skyloft.skypython.ui.drawer.SkyDrawer
import dev.skyloft.skypython.ui.theme.BarBlue
import dev.skyloft.skypython.ui.theme.EditorBackground
import dev.skyloft.skypython.ui.theme.RunAmber
import kotlinx.coroutines.launch
import java.io.File

private val TabSelected = Color.White
private val TabIdle = Color(0xFF93A4B3)
private val SubtitleColor = Color(0xFFB3C2CE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    settings: EditorSettings,
    onRun: (File) -> Unit,
    onOpenFiles: () -> Unit,
    onDrawerAction: (DrawerAction) -> Unit,
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
    val config = remember(settings) { EditorViewConfig.from(settings) }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-python"),
    ) { uri -> uri?.let(viewModel::saveAs) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    // ON_PAUSE always precedes finishing/backgrounding; ON_STOP is kept as a cheap second chance
    // (it is a no-op when nothing is dirty). Saves are NonCancellable, see DocumentSaver.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { controller.flushPendingText(); viewModel.saveAll() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.flushPendingText(); viewModel.saveAll() }
    // Edits reach the ViewModel debounced; flush before anything that reads the text.
    val openFiles = { controller.flushPendingText(); onOpenFiles() }

    val current = state.current

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Opened by ☰ only: an edge swipe would fight horizontal scrolling in the code view.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            SkyDrawer(fileName = current?.name, cursor = cursor.value) { action ->
                scope.launch { drawerState.close() }
                controller.flushPendingText()
                onDrawerAction(action)
            }
        },
    ) {
        Scaffold(
            containerColor = EditorBackground,
            topBar = {
                Column(Modifier.background(BarBlue)) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = BarBlue,
                            navigationIconContentColor = Color.White,
                            titleContentColor = Color.White,
                            actionIconContentColor = Color.White,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        },
                        title = { EditorTitle(current, viewModel.projectsRoot) },
                        actions = {
                            IconButton(onClick = openFiles) {
                                Icon(Icons.Outlined.Folder, contentDescription = "Files")
                            }
                            IconButton(onClick = controller::undo, enabled = current != null) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Redo") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Redo, null) },
                                        enabled = current != null,
                                        onClick = { menuOpen = false; controller.redo() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save") },
                                        leadingIcon = { Icon(Icons.Filled.Save, null) },
                                        enabled = current != null,
                                        onClick = {
                                            menuOpen = false
                                            controller.flushPendingText()
                                            viewModel.saveCurrent()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save as…") },
                                        leadingIcon = { Icon(Icons.Filled.SaveAs, null) },
                                        enabled = current != null,
                                        onClick = {
                                            menuOpen = false
                                            controller.flushPendingText()
                                            current?.let { saveAsLauncher.launch(it.name) }
                                        },
                                    )
                                }
                            }
                        },
                    )
                    if (current != null) {
                        DocTabs(state, viewModel, onClose = { id -> controller.flushPendingText(); viewModel.close(id) })
                    }
                }
            },
            floatingActionButton = {
                if (current != null) {
                    FloatingActionButton(
                        onClick = {
                            if (running) return@FloatingActionButton
                            running = true
                            controller.flushPendingText()
                            scope.launch {
                                try {
                                    viewModel.runCurrent()?.let(onRun)
                                } finally {
                                    running = false
                                }
                            }
                        },
                        shape = CircleShape,
                        containerColor = RunAmber,
                        contentColor = Color(0xFF2B2200),
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                        modifier = Modifier.size(64.dp),
                    ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Run", modifier = Modifier.size(36.dp)) }
                }
            },
            bottomBar = {
                if (current != null) {
                    SymbolBar(onInsert = controller::insert, modifier = Modifier.navigationBarsPadding().imePadding())
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    current == null -> EmptyEditor(openFiles)
                    else -> CodeEditor(
                        docId = current.id,
                        initialText = current.text,
                        openIds = state.docs.mapTo(HashSet()) { it.id },
                        controller = controller,
                        config = config,
                        onTextChanged = viewModel::onTextChanged,
                        onCursorChanged = { cursor.value = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

/** File name (bold) over its folder path, both ellipsized, like a classic IDE title. */
@Composable
private fun EditorTitle(doc: OpenDocument?, projectsRoot: File) {
    if (doc == null) {
        Text("SkyPython", fontWeight = FontWeight.Bold)
        return
    }
    Column {
        Text(
            doc.name,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            docSubtitle(doc, projectsRoot),
            fontSize = 14.sp,
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
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Close") }, onClick = {
                        menu = false
                        onClose(doc.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun EmptyEditor(onOpenFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No open files", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onOpenFiles) { Text("Open Files") }
    }
}
