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
import dev.skyloft.skypython.ui.theme.SkyTopBar
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
import dev.skyloft.skypython.editor.PROJECTS_LABEL
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.skyloft.skypython.ui.theme.BarNavy
import dev.skyloft.skypython.ui.theme.DividerNavy
import dev.skyloft.skypython.ui.theme.EditorBackground
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.GlowLine
import dev.skyloft.skypython.ui.theme.GradientFab
import dev.skyloft.skypython.ui.theme.SoftViolet
import dev.skyloft.skypython.ui.theme.SkyPythonTheme
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R

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
            title = stringResource(R.string.files_new_file),
            confirmLabel = stringResource(R.string.action_create),
            initial = "",
            serverError = dialogError,
            onClearServerError = { dialogError = null },
            onConfirm = { name -> viewModel.createFile(name, onOpResult) },
            onDismiss = ::closeDialog,
        )
        FilesDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.files_new_folder),
            confirmLabel = stringResource(R.string.action_create),
            initial = "",
            serverError = dialogError,
            onClearServerError = { dialogError = null },
            onConfirm = { name -> viewModel.createDir(name, onOpResult) },
            onDismiss = ::closeDialog,
        )
        is FilesDialog.Rename -> NameDialog(
            title = stringResource(if (d.entry.isDirectory) R.string.files_rename_folder else R.string.files_rename_file),
            confirmLabel = stringResource(R.string.action_rename),
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
        containerColor = EditorBackground,
        topBar = {
            SkyTopBar(
                title = stringResource(R.string.title_files),
                onBack = onBack,
                glow = false,
                actions = {
                    IconButton(onClick = onOpenExternal) {
                        Icon(Icons.Filled.FileOpen, contentDescription = stringResource(R.string.files_open_external))
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
            GlowLine()
            if (state.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.files_empty_folder),
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
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = DividerNavy)
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
            .background(BarNavy)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp, enabled = state.canGoUp) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = stringResource(R.string.files_up_one_folder),
                tint = if (state.canGoUp) Color.White else MutedText.copy(alpha = 0.4f),
            )
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
                        tint = MutedText,
                    )
                }
                val isLast = index == state.breadcrumbs.lastIndex
                TextButton(onClick = { onNavigate(crumb.dir) }, enabled = !isLast) {
                    Text(
                        // The root is called "Projects" everywhere else (editor subtitle).
                        if (index == 0) stringResource(R.string.projects_label) else crumb.label,
                        maxLines = 1,
                        color = if (isLast) Color.White else MutedText,
                        fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                when {
                    entry.isDirectory -> Icons.Filled.Folder
                    entry.name.endsWith(".py") -> Icons.Filled.Code
                    else -> Icons.Filled.Description
                },
                contentDescription = stringResource(if (entry.isDirectory) R.string.files_folder else R.string.files_file),
                tint = if (entry.isDirectory) SoftViolet else MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.files_more_options, entry.name))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
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
        GradientFab(onClick = { menuOpen = true }, icon = Icons.Filled.Add, contentDescription = stringResource(R.string.files_new))
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_new_file)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onNewFile()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_new_folder)) },
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
    val localError = if (value.text.isEmpty()) null else nameProblem(trimmed)?.let { stringResource(it.messageRes()) }
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
                label = { Text(stringResource(R.string.files_name)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                // File names, not prose: no auto-capitals or autocorrect; Done confirms.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(trimmed) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeleteDialog(entry: FileEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (entry.isDirectory) R.string.files_delete_folder_title else R.string.files_delete_file_title)) },
        text = {
            Text(
                stringResource(
                    if (entry.isDirectory) R.string.files_delete_folder_text else R.string.files_delete_file_text,
                    entry.name,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
