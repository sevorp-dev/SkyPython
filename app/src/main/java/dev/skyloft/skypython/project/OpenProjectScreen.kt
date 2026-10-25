package dev.skyloft.skypython.project

import android.app.Activity
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.R
import dev.skyloft.skypython.files.FileEntry
import dev.skyloft.skypython.files.NameDialog
import dev.skyloft.skypython.files.Workspace
import dev.skyloft.skypython.files.describeFileError
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.ui.rememberMainScope
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.OrangeSoft
import dev.skyloft.skypython.ui.theme.OrangeTint
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.SkyTopBar
import dev.skyloft.skypython.ui.theme.Slate
import dev.skyloft.skypython.ui.theme.SlateBar
import dev.skyloft.skypython.ui.theme.SlateLine
import dev.skyloft.skypython.ui.theme.SlatePanel
import dev.skyloft.skypython.ui.theme.TextBright
import dev.skyloft.skypython.ui.theme.brandButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the screen shows: the start page, the explanation before asking for access, or a folder. */
private sealed interface OpenStep {
    data object Home : OpenStep
    data class Explain(val then: () -> Unit) : OpenStep
    data class Browse(val root: StorageRoot, val dir: File) : OpenStep
}

/**
 * "Open project": the app's own projects, recent project folders and a browser of the phone's
 * storage. Shared storage needs access first; the explanation screen comes before the system's.
 *
 * @param onOpened a project was opened (it is now [ProjectsViewModel.current]).
 */
@Composable
fun OpenProjectScreen(viewModel: ProjectsViewModel, onOpened: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val current by viewModel.current.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val roots = remember { viewModel.roots() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberMainScope()
    var step by remember { mutableStateOf<OpenStep>(OpenStep.Home) }
    var denied by rememberSaveable { mutableStateOf(false) }

    val rootLabel = rootLabeler()
    fun open(dir: File) = viewModel.open(dir) { error ->
        if (error == null) onOpened() else scope.launch { snackbar.showSnackbar(error) }
    }
    /** Runs [action] now if [dir] needs no access or access is there; else explains first. */
    fun withAccess(dir: File, action: () -> Unit) {
        viewModel.refreshAccess()
        if (!viewModel.needsPermission(dir) || viewModel.access.value) action() else step = OpenStep.Explain(action)
    }

    // Back from the Settings page (Android 11+): carry on with what the user was doing.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAccess() }
    LaunchedEffect(access, step) {
        val s = step
        if (access && s is OpenStep.Explain) {
            step = OpenStep.Home
            s.then()
        }
    }
    val askRuntime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshAccess()
        if (!viewModel.access.value) denied = true
    }

    when (val s = step) {
        OpenStep.Home -> BackHandler { onBack() }
        is OpenStep.Explain -> BackHandler { step = OpenStep.Home }
        is OpenStep.Browse -> BackHandler {
            val parent = FolderListing.parentWithin(s.dir, s.root.dir)
            step = if (parent != null) s.copy(dir = parent) else OpenStep.Home
        }
    }

    when (val s = step) {
        OpenStep.Home -> HomeContent(
            current = current,
            privateRoot = viewModel.privateRoot,
            recents = recents,
            roots = roots,
            rootLabel = rootLabel,
            snackbar = snackbar,
            onBack = onBack,
            onPrivate = {
                viewModel.openPrivate()
                onOpened()
            },
            onRecent = { path -> File(path).let { dir -> withAccess(dir) { open(dir) } } },
            onForget = viewModel::forget,
            onRoot = { root -> withAccess(root.dir) { step = OpenStep.Browse(root, root.dir) } },
        )
        is OpenStep.Explain -> PermissionContent(
            request = StoragePermission.request,
            denied = denied,
            onAllow = {
                when (StoragePermission.request) {
                    StorageAccess.Request.AllFilesSettings -> StoragePermission.openAllFilesSettings(context)
                    StorageAccess.Request.RuntimePermission -> {
                        val activity = context as? Activity
                        // Denied for good: only the app's settings page can grant it now.
                        val permanentlyDenied = denied && activity != null &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(activity, StoragePermission.RUNTIME_PERMISSIONS[1])
                        if (permanentlyDenied) StoragePermission.openAppSettings(context)
                        else askRuntime.launch(StoragePermission.RUNTIME_PERMISSIONS)
                    }
                }
            },
            onNotNow = { step = OpenStep.Home },
        )
        is OpenStep.Browse -> BrowseContent(
            root = s.root,
            dir = s.dir,
            rootLabel = rootLabel(s.root),
            snackbar = snackbar,
            onBack = { step = OpenStep.Home },
            onEnter = { step = s.copy(dir = it) },
            onNewFolder = { name, onResult ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { Workspace(s.dir).createDir(s.dir, name.trim()) }
                    result.onSuccess { step = s.copy(dir = it); onResult(null) }
                        .onFailure { onResult(describeFileError(AppLocale.localized(context), it)) }
                }
            },
            onUse = { open(s.dir) },
        )
    }
}

@Composable
private fun rootLabeler(): (StorageRoot) -> String {
    val internal = stringResource(R.string.projects_root_internal)
    val card = stringResource(R.string.projects_root_sd)
    return { root -> if (root.kind == StorageRoot.Kind.Internal) internal else "$card (${root.dir.name})" }
}

@Composable
private fun HomeContent(
    current: File,
    privateRoot: File,
    recents: List<RecentProject>,
    roots: List<StorageRoot>,
    rootLabel: (StorageRoot) -> String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onPrivate: () -> Unit,
    onRecent: (String) -> Unit,
    onForget: (String) -> Unit,
    onRoot: (StorageRoot) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    Scaffold(
        containerColor = Slate,
        topBar = { SkyTopBar(title = stringResource(R.string.projects_open_project), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionLabel(stringResource(R.string.projects_section_app), Modifier.padding(top = 4.dp, bottom = 4.dp)) }
            item {
                Card {
                    ProjectRow(
                        icon = Icons.Outlined.Inventory2,
                        title = stringResource(R.string.projects_section_app),
                        subtitle = stringResource(R.string.projects_app_desc),
                        badge = if (current.path == privateRoot.path) stringResource(R.string.projects_current) else null,
                        onClick = onPrivate,
                    )
                }
            }
            item { SectionLabel(stringResource(R.string.projects_section_recent), Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            item {
                Card {
                    if (recents.isEmpty()) {
                        Text(
                            stringResource(R.string.projects_no_recent),
                            color = MutedText,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    recents.forEachIndexed { i, recent ->
                        if (i > 0) Hairline(Modifier.padding(start = 68.dp))
                        val dir = File(recent.path)
                        val name = dir.name.ifEmpty { dir.path }
                        val whenText = if (now - recent.openedAt < DateUtils.MINUTE_IN_MILLIS) {
                            stringResource(R.string.projects_just_now)
                        } else {
                            DateUtils.getRelativeTimeSpanString(recent.openedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
                        }
                        ProjectRow(
                            icon = Icons.Outlined.History,
                            title = name,
                            subtitle = friendlyPath(dir, roots, rootLabel),
                            detail = stringResource(R.string.projects_opened_at, whenText),
                            badge = if (current.path == recent.path) stringResource(R.string.projects_current) else null,
                            onClick = { onRecent(recent.path) },
                            trailing = {
                                IconButton(onClick = { onForget(recent.path) }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.projects_remove_recent, name),
                                        tint = MutedText,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.projects_section_storage), Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            item {
                Card {
                    roots.forEachIndexed { i, root ->
                        if (i > 0) Hairline(Modifier.padding(start = 68.dp))
                        ProjectRow(
                            icon = if (root.kind == StorageRoot.Kind.Internal) Icons.Outlined.PhoneAndroid else Icons.Outlined.SdCard,
                            title = rootLabel(root),
                            subtitle = stringResource(R.string.projects_root_desc),
                            onClick = { onRoot(root) },
                            trailing = {
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MutedText)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A list card: [SlatePanel] surface, 12 dp corners, a 1 dp [SlateLine] border. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SlatePanel)
            .border(1.dp, SlateLine, shape),
    ) { content() }
}

/** An icon on a tinted circle. */
@Composable
private fun CircleIcon(icon: ImageVector, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(OrangeTint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = OrangeSoft, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun ProjectRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    detail: String? = null,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIcon(icon)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = TextBright,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Text(
                        badge,
                        color = OrangeSoft,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(OrangeTint)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, color = MutedText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = MutedText.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1)
        }
        trailing?.invoke()
    }
}

/** Why SkyPython asks for files access, before the system dialog or Settings page. */
@Composable
private fun PermissionContent(
    request: StorageAccess.Request,
    denied: Boolean,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
) {
    Scaffold(
        containerColor = Slate,
        topBar = { SkyTopBar(title = stringResource(R.string.projects_open_project), onBack = onNotNow) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            CircleIcon(Icons.Outlined.FolderOpen, size = 72)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.storage_title),
                color = TextBright,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.storage_text),
                color = MutedText,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Card {
                Point(Icons.Outlined.PlayArrow, stringResource(R.string.storage_point_run))
                Hairline(Modifier.padding(start = 68.dp))
                Point(Icons.Outlined.Lock, stringResource(R.string.storage_point_private))
                Hairline(Modifier.padding(start = 68.dp))
                Point(Icons.Outlined.Inventory2, stringResource(R.string.storage_point_optional))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(
                    when {
                        denied -> R.string.storage_denied
                        request == StorageAccess.Request.AllFilesSettings -> R.string.storage_steps_all_files
                        else -> R.string.storage_steps_runtime
                    },
                ),
                color = if (denied) OrangeSoft else MutedText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAllow, colors = brandButtonColors(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(if (denied) R.string.storage_open_settings else R.string.storage_allow), fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onNotNow, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.crash_dialog_later), color = MutedText)
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleIcon(icon)
        Text(text, color = TextBright, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp))
    }
}

/** The folder browser: breadcrumbs, folders (files shown dimmed), "Use this folder". */
@Composable
private fun BrowseContent(
    root: StorageRoot,
    dir: File,
    rootLabel: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onEnter: (File) -> Unit,
    onNewFolder: (String, (String?) -> Unit) -> Unit,
    onUse: () -> Unit,
) {
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(dir, showHidden) {
        entries = withContext(Dispatchers.IO) { FolderListing.list(dir, showHidden) }
    }
    val crumbs = remember(dir, root) { FolderListing.crumbs(dir, root.dir) }
    val parent = FolderListing.parentWithin(dir, root.dir)
    Scaffold(
        containerColor = Slate,
        topBar = {
            SkyTopBar(
                title = stringResource(R.string.projects_browse_title),
                onBack = onBack,
                divider = false,
                actions = {
                    IconButton(onClick = { showHidden = !showHidden }) {
                        Icon(
                            if (showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = stringResource(R.string.projects_show_hidden),
                            tint = if (showHidden) OrangeSoft else TextBright,
                        )
                    }
                    IconButton(onClick = { newFolder = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.files_new_folder))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(SlateBar).navigationBarsPadding()) {
                Hairline()
                Button(
                    onClick = onUse,
                    colors = brandButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(48.dp),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.projects_use_folder) + " · " + (if (crumbs.size == 1) rootLabel else dir.name),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().background(SlateBar).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { parent?.let(onEnter) }, enabled = parent != null) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        contentDescription = stringResource(R.string.files_up_one_folder),
                        tint = if (parent != null) TextBright else MutedText.copy(alpha = 0.5f),
                    )
                }
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    crumbs.forEachIndexed { i, crumb ->
                        if (i > 0) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MutedText,
                            )
                        }
                        val last = i == crumbs.lastIndex
                        TextButton(onClick = { onEnter(crumb.dir) }, enabled = !last) {
                            Text(
                                if (i == 0) rootLabel else crumb.label,
                                maxLines = 1,
                                color = if (last) TextBright else MutedText,
                                fontWeight = if (last) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Hairline()
            val list = entries
            if (list != null && list.none { it.isDirectory }) {
                Text(
                    stringResource(R.string.projects_no_folders),
                    color = MutedText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (list != null && list.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    item {
                        Card {
                            list.forEachIndexed { i, entry ->
                                if (i > 0) Hairline(Modifier.padding(start = 68.dp))
                                BrowseRow(entry) { onEnter(entry.file) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (newFolder) {
        NewFolderDialog(
            onCreate = { name, onResult -> onNewFolder(name) { error -> if (error == null) newFolder = false; onResult(error) } },
            onDismiss = { newFolder = false },
        )
    }
}

@Composable
private fun BrowseRow(entry: FileEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (entry.isDirectory) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) {
            CircleIcon(Icons.Outlined.Folder)
        } else {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = MutedText.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
            }
        }
        Text(
            entry.name,
            color = if (entry.isDirectory) TextBright else MutedText.copy(alpha = 0.7f),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        if (entry.isDirectory) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MutedText)
    }
}

@Composable
private fun NewFolderDialog(onCreate: (String, (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    NameDialog(
        title = stringResource(R.string.files_new_folder),
        confirmLabel = stringResource(R.string.action_create),
        initial = "",
        serverError = error,
        onClearServerError = { error = null },
        onConfirm = { name -> onCreate(name) { error = it } },
        onDismiss = onDismiss,
    )
}
