package dev.skyloft.skypython.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel, onRun: (File) -> Unit, onOpenFiles: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = remember { EditorController() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-python"),
    ) { uri -> uri?.let(viewModel::saveAs) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveAll() }

    val current = state.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SkyPython") },
                actions = {
                    IconButton(onClick = controller::undo, enabled = current != null) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = controller::redo, enabled = current != null) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { viewModel.saveCurrent() }, enabled = current != null) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Filled.Folder, contentDescription = "Files")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Save as…") },
                                enabled = current != null,
                                onClick = {
                                    menuOpen = false
                                    current?.let { saveAsLauncher.launch(it.name) }
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (current != null) {
                FloatingActionButton(
                    onClick = {
                        if (running) return@FloatingActionButton
                        running = true
                        scope.launch {
                            try {
                                viewModel.runCurrent()?.let(onRun)
                            } finally {
                                running = false
                            }
                        }
                    },
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Run") }
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
                current == null -> EmptyEditor(onOpenFiles)
                else -> {
                    DocTabs(state, viewModel)
                    CodeEditor(
                        docId = current.id,
                        initialText = current.text,
                        openIds = state.docs.mapTo(HashSet()) { it.id },
                        controller = controller,
                        onTextChanged = viewModel::onTextChanged,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DocTabs(state: EditorUiState, viewModel: EditorViewModel) {
    val selected = state.docs.indexOfFirst { it.id == state.currentId }.coerceAtLeast(0)
    PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp) {
        state.docs.forEachIndexed { index, doc ->
            var menu by remember(doc.id) { mutableStateOf(false) }
            Tab(selected = index == selected, onClick = { viewModel.select(doc.id) }) {
                Box(
                    Modifier
                        .combinedClickable(
                            onClick = { viewModel.select(doc.id) },
                            onLongClick = { menu = true },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    val label = viewModel.labelFor(doc, state.docs)
                    Text(
                        if (doc.dirty) "• $label" else label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Close") }, onClick = {
                            menu = false
                            viewModel.close(doc.id)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyEditor(onOpenFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No open files", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onOpenFiles) { Text("Open Files") }
    }
}
