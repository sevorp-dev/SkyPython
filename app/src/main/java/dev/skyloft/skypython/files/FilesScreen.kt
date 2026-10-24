package dev.skyloft.skypython.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.ui.theme.SkyPythonTheme
import java.io.File

private val OPEN_MIME_TYPES = arrayOf("text/*", "application/octet-stream")

/** What dialog, if any, is showing. */
private sealed interface FilesDialog {
    data object NewFile : FilesDialog
    data object NewFolder : FilesDialog
    data class Rename(val entry: FileEntry) : FilesDialog
    data class Delete(val entry: FileEntry) : FilesDialog
}

/**
 * File manager for the workspace (`filesDir/projects`).
 *
 * @param onOpenFile a workspace file was tapped; the caller opens it and navigates back to the editor.
 * @param onOpenExternal a document was picked via SAF; a persistable permission has already been requested.
 * @param onDeleted a file or folder was deleted (folders recursively) so the editor can close affected tabs.
 * @param onBack leave the Files screen.
 * @param onRenamed a file or folder was renamed so the editor can re-point affected tabs.
 */
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    onOpenFile: (File) -> Unit,
    onOpenExternal: (Uri) -> Unit,
    onDeleted: (File) -> Unit,
    onBack: () -> Unit,
    onRenamed: (old: File, new: File) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takePersistablePermission(context.contentResolver, uri)
            onOpenExternal(uri)
        }
    }

    // Pick up changes made elsewhere (e.g. the editor saved a new file) whenever the screen is shown.
    LaunchedEffect(Unit) { viewModel.refresh() }

    BackHandler(enabled = state.canGoUp) { viewModel.up() }

    var dialog by remember { mutableStateOf<FilesDialog?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    fun closeDialog() {
        dialog = null
        dialogError = null
    }
    val onOpResult: (String?) -> Unit = { error -> if (error == null) closeDialog() else dialogError = error }

    FilesContent(
        state = state,
        onBack = onBack,
        onUp = viewModel::up,
        onNavigate = viewModel::enter,
        onEntryClick = { entry -> if (entry.isDirectory) viewModel.enter(entry.file) else onOpenFile(entry.file) },
        onOpenExternal = { openDocument.launch(OPEN_MIME_TYPES) },
        onNewFile = { dialog = FilesDialog.NewFile },
        onNewFolder = { dialog = FilesDialog.NewFolder },
        onRename = { dialog = FilesDialog.Rename(it) },
        onDelete = { dialog = FilesDialog.Delete(it) },
        onErrorShown = viewModel::clearError,
    )

    when (val d = dialog) {
        null -> Unit
        FilesDialog.NewFile -> NameDialog(
            title = "New file",
            confirmLabel = "Create",
            initial = "",
            serverError = dialogError,
            onClearServerError = { dialogError = null },
            onConfirm = { name -> viewModel.createFile(name, onOpResult) },
            onDismiss = ::closeDialog,
        )
        FilesDialog.NewFolder -> NameDialog(
            title = "New folder",
            confirmLabel = "Create",
            initial = "",
            serverError = dialogError,
            onClearServerError = { dialogError = null },
            onConfirm = { name -> viewModel.createDir(name, onOpResult) },
            onDismiss = ::closeDialog,
        )
        is FilesDialog.Rename -> NameDialog(
            title = if (d.entry.isDirectory) "Rename folder" else "Rename file",
            confirmLabel = "Rename",
            initial = d.entry.name,
            serverError = dialogError,
            onClearServerError = { dialogError = null },
            onConfirm = { name ->
                if (name.trim() == d.entry.name) closeDialog()
                else viewModel.rename(d.entry.file, name) { error ->
                    onOpResult(error)
                    if (error == null) onRenamed(d.entry.file, File(d.entry.file.parentFile, name.trim()))
                }
            },
            onDismiss = ::closeDialog,
        )
        is FilesDialog.Delete -> DeleteDialog(
            entry = d.entry,
            onConfirm = {
                closeDialog()
                viewModel.delete(d.entry.file) { error -> if (error == null) onDeleted(d.entry.file) }
            },
            onDismiss = ::closeDialog,
        )
    }
}

private fun takePersistablePermission(resolver: android.content.ContentResolver, uri: Uri) {
    try {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    } catch (e: SecurityException) {
        // The provider may not grant write access; keep at least read access across restarts.
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not persistable at all: the URI still works for this session.
        }
    }
}

/** Stateless UI of the Files screen (previewable). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesContent(
    state: FilesState,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onNavigate: (File) -> Unit,
    onEntryClick: (FileEntry) -> Unit,
    onOpenExternal: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onErrorShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        onErrorShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenExternal) {
                        Icon(Icons.Filled.FileOpen, contentDescription = "Open external…")
                    }
                },
            )
        },
        floatingActionButton = { NewItemFab(onNewFile = onNewFile, onNewFolder = onNewFolder) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Breadcrumbs(state = state, onUp = onUp, onNavigate = onNavigate)
            HorizontalDivider()
            if (state.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "This folder is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp), // keep the last row clear of the FAB
                ) {
                    items(state.entries, key = { it.file.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                            onRename = { onRename(entry) },
                            onDelete = { onDelete(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(state: FilesState, onUp: () -> Unit, onNavigate: (File) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp, enabled = state.canGoUp) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one folder")
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState(), reverseScrolling = true),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.breadcrumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val isLast = index == state.breadcrumbs.lastIndex
                TextButton(onClick = { onNavigate(crumb.dir) }, enabled = !isLast) {
                    Text(
                        crumb.label,
                        maxLines = 1,
                        color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FileEntry, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = if (entry.isDirectory) "Folder" else "File",
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${entry.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun NewItemFab(onNewFile: () -> Unit, onNewFolder: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.Add, contentDescription = "New")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("New file") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onNewFile()
                },
            )
            DropdownMenuItem(
                text = { Text("New folder") },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onNewFolder()
                },
            )
        }
    }
}

/**
 * Name input dialog. Shows [nameError] inline while typing, and [serverError]
 * (e.g. "already exists") after a failed confirm until the text changes.
 */
@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    serverError: String?,
    onClearServerError: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Preselect the base name (without extension) so typing replaces just that part.
    var value by remember {
        val dot = initial.lastIndexOf('.')
        val end = if (dot > 0) dot else initial.length
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, end)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val trimmed = value.text.trim()
    val localError = if (value.text.isEmpty()) null else nameError(trimmed)
    val error = localError ?: serverError
    val canConfirm = trimmed.isNotEmpty() && localError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    if (it.text != value.text) onClearServerError()
                    value = it
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text("Name") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(entry: FileEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.isDirectory) "Delete folder?" else "Delete file?") },
        text = {
            Text(
                if (entry.isDirectory) {
                    "\"${entry.name}\" and everything inside it will be permanently deleted."
                } else {
                    "\"${entry.name}\" will be permanently deleted."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun FilesContentPreview() {
    val root = File("/data/user/0/dev.skyloft.skypython/files/projects")
    val dir = File(root, "test dir")
    SkyPythonTheme {
        FilesContent(
            state = FilesState(
                currentDir = dir,
                breadcrumbs = listOf(Crumb("projects", root), Crumb("test dir", dir)),
                entries = listOf(
                    FileEntry(File(dir, "utils"), isDirectory = true),
                    FileEntry(File(dir, "a b.py"), isDirectory = false),
                    FileEntry(File(dir, "main.py"), isDirectory = false),
                ),
                canGoUp = true,
            ),
            onBack = {},
            onUp = {},
            onNavigate = {},
            onEntryClick = {},
            onOpenExternal = {},
            onNewFile = {},
            onNewFolder = {},
            onRename = {},
            onDelete = {},
            onErrorShown = {},
        )
    }
}
