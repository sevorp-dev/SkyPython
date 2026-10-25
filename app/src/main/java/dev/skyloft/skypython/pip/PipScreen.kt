package dev.skyloft.skypython.pip

import dev.skyloft.skypython.ui.theme.TextBright
import dev.skyloft.skypython.ui.theme.SlatePanel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import dev.skyloft.skypython.ui.theme.SlateLine
import dev.skyloft.skypython.ui.theme.Ink
import dev.skyloft.skypython.ui.theme.SuccessGreen
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.Slate
import dev.skyloft.skypython.ui.theme.Orange
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.TabRowDefaults
import dev.skyloft.skypython.ui.theme.CodeFont
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.R
import dev.skyloft.skypython.ui.theme.SlateBar
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.brandButtonColors
import dev.skyloft.skypython.ui.theme.SkyTopBar
import kotlinx.coroutines.launch
import java.io.File

/**
 * Pip: search PyPI and a popular list, the installed packages (upgrade, uninstall, details)
 * and `pip install -r`. The running operation's log sits at the bottom of every tab.
 *
 * @param currentFile the file open in the editor, offered on the requirements tab if it's a `.txt`.
 * @param projectsRoot to show requirements files relative to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipScreen(
    viewModel: PipViewModel,
    onBack: () -> Unit,
    currentFile: File? = null,
    projectsRoot: File? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notice = state.notice
    val noticeText = notice?.let { noticeText(it) }
    LaunchedEffect(notice) {
        if (notice != null && noticeText != null) {
            // Its own scope: clearing the notice must not dismiss the snackbar.
            scope.launch { snackbar.showSnackbar(noticeText) }
            viewModel.noticeShown()
        }
    }

    Scaffold(
        topBar = {
            SkyTopBar(
                title = stringResource(R.string.title_pip),
                onBack = onBack,
                divider = false,
                actions = {
                    if (state.tab == PipTab.Installed) {
                        IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            PrimaryTabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = Slate,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        Modifier.tabIndicatorOffset(state.tab.ordinal, matchContentSize = false),
                        width = Dp.Unspecified,
                        height = 2.dp,
                        color = Orange,
                    )
                },
                divider = {},
            ) {
                PipTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                stringResource(tab.labelRes()),
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Hairline()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.tab) {
                    PipTab.Search -> SearchTab(state, viewModel)
                    PipTab.Installed -> InstalledTab(state, viewModel)
                    PipTab.Requirements -> RequirementsTab(state, viewModel, currentFile, projectsRoot)
                }
            }
            if (state.installing || state.log.isNotEmpty()) {
                OperationPanel(state, onClose = viewModel::clearLog)
            }
        }
    }

    state.details?.let { details -> DetailsSheet(details, state, viewModel) }

    state.confirmUninstall?.let { pkg ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUninstall,
            title = { Text(stringResource(R.string.pip_uninstall_title, pkg.name)) },
            text = { Text(stringResource(R.string.pip_uninstall_text, pkg.version)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUninstall) {
                    Text(stringResource(R.string.pip_uninstall), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUninstall) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

// --- Search ------------------------------------------------------------------------------------

@Composable
private fun SearchTab(state: PipState, viewModel: PipViewModel) {
    val is64Bit = remember { android.os.Process.is64Bit() }
    val popular = remember(state.spec) { PopularPackages.matching(state.spec) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = state.spec,
                onValueChange = viewModel::onSpecChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.pip_search_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                placeholder = { Text(stringResource(R.string.pip_package_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (state.spec.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.onSpecChange("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.pip_clear))
                        }
                    }
                } else {
                    null
                },
                isError = state.specError != null,
                supportingText = state.specError?.let { { Text(stringResource(it.messageRes())) } },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = CodeFont),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                colors = brandButtonColors(),
                onClick = viewModel::search,
                enabled = !state.searching,
                shape = RoundedCornerShape(12.dp),
                // The outlined field reserves 8dp above its box for the floating label.
                modifier = Modifier.padding(top = 8.dp).height(56.dp),
            ) { Text(stringResource(R.string.pip_search_action)) }
        }
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))

        // The result goes in above the popular rows, and a lazy list keeps its first visible row
        // in place when items are inserted before it: without this the result would land above
        // the viewport, out of sight (always so on a small screen with the keyboard up).
        val listState = rememberLazyListState()
        LaunchedEffect(state.lookup) {
            if (state.lookup != null) listState.scrollToItem(0)
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            state.lookup?.let { lookup ->
                item(key = "result") {
                    LookupCard(lookup, state, onInstall = viewModel::installPackage)
                }
            }
            item(key = "popular-label") {
                SectionLabel(
                    stringResource(R.string.pip_popular),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            if (popular.isEmpty()) {
                item(key = "popular-none") {
                    Text(
                        stringResource(R.string.pip_popular_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(popular, key = { "p-" + it.name }) { pkg ->
                PopularRow(
                    pkg = pkg,
                    installed = state.isInstalled(pkg.name),
                    is64Bit = is64Bit,
                    busy = state.installing,
                    onInstall = { viewModel.installPackage(pkg.name) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "intro") {
                Text(
                    stringResource(R.string.pip_intro) + " " + stringResource(R.string.pip_pure_python_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun LookupCard(lookup: LookupResult, state: PipState, onInstall: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = SlatePanel, contentColor = TextBright),
        border = BorderStroke(1.dp, SlateLine),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (lookup) {
                is LookupResult.Found -> FoundProject(lookup.project, state, onInstall)
                is LookupResult.NotFound -> IconLine(
                    Icons.Outlined.Info,
                    stringResource(R.string.pip_result_not_found, lookup.name),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is LookupResult.Offline -> IconLine(
                    Icons.Outlined.Warning,
                    stringResource(R.string.pip_result_offline),
                    MaterialTheme.colorScheme.error,
                )
                is LookupResult.Failed -> IconLine(
                    Icons.Outlined.Error,
                    stringResource(R.string.pip_result_failed, lookup.message),
                    MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FoundProject(project: PyPiProject, state: PipState, onInstall: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            project.name,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = CodeFont,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (state.isInstalled(project.name)) {
            Spacer(Modifier.width(8.dp))
            InstalledBadge()
        }
    }
    Text(
        stringResource(R.string.pip_latest_version, project.version),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = CodeFont,
    )
    project.summary?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    project.license?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.pip_license, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(8.dp))
    val (icon, text, color) = when (project.wheel) {
        WheelSupport.Pure -> Triple(Icons.Outlined.CheckCircle, R.string.pip_wheel_pure, SuccessGreen)
        WheelSupport.PrebuiltAndroid -> Triple(Icons.Outlined.CheckCircle, R.string.pip_wheel_android, SuccessGreen)
        WheelSupport.SourceOnly -> Triple(Icons.Outlined.Info, R.string.pip_wheel_source, MaterialTheme.colorScheme.tertiary)
        WheelSupport.NativeOnly -> Triple(Icons.Outlined.Warning, R.string.pip_wheel_native, MaterialTheme.colorScheme.error)
        WheelSupport.NoFiles -> Triple(Icons.Outlined.Warning, R.string.pip_wheel_none, MaterialTheme.colorScheme.error)
    }
    IconLine(icon, stringResource(text), color)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (project.wheel) {
            WheelSupport.Pure, WheelSupport.PrebuiltAndroid, WheelSupport.SourceOnly -> Button(
                colors = brandButtonColors(),
                onClick = { onInstall(project.name) },
                enabled = !state.installing,
            ) { Text(stringResource(R.string.pip_install)) }
            WheelSupport.NativeOnly -> OutlinedButton(
                onClick = { onInstall(project.name) },
                enabled = !state.installing,
            ) { Text(stringResource(R.string.pip_install_anyway)) }
            WheelSupport.NoFiles -> Unit
        }
        Spacer(Modifier.weight(1f))
        project.homePage?.let { url ->
            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                Text(stringResource(R.string.pip_home_page), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PopularRow(pkg: PopularPackage, installed: Boolean, is64Bit: Boolean, busy: Boolean, onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                pkg.name,
                fontFamily = CodeFont,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(pkg.summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            pkg.availability == Availability.ComingLater -> DisabledNote(stringResource(R.string.pip_coming_later))
            pkg.availability == Availability.Needs64Bit && !is64Bit -> DisabledNote(stringResource(R.string.pip_needs_64bit))
            installed -> InstalledBadge()
            else -> TextButton(onClick = onInstall, enabled = !busy) { Text(stringResource(R.string.pip_install)) }
        }
    }
}

@Composable
private fun DisabledNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun InstalledBadge() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.pip_installed_badge),
            style = MaterialTheme.typography.labelMedium,
            color = SuccessGreen,
        )
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- Installed ---------------------------------------------------------------------------------

@Composable
private fun InstalledTab(state: PipState, viewModel: PipViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.listError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = CodeFont,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (state.packages.isEmpty() && !state.loading && state.listError == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pip_no_packages), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        when {
            state.checkingUpdates -> StatusLine(stringResource(R.string.pip_checking_updates))
            state.updatesFailed -> StatusLine(stringResource(R.string.pip_updates_failed))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.packages, key = { it.name }) { pkg ->
                PackageRow(
                    pkg = pkg,
                    update = state.updateFor(pkg),
                    busy = state.uninstalling == pkg.name || (state.running as? PipOperation.Upgrade)?.name == pkg.name,
                    enabled = state.uninstalling == null && !state.installing,
                    onOpen = { viewModel.openDetails(pkg) },
                    onUpgrade = { viewModel.upgrade(pkg) },
                    onUninstall = { viewModel.requestUninstall(pkg) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun PackageRow(
    pkg: PipPackage,
    update: String?,
    busy: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onUpgrade: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(pkg.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pkg.version,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = CodeFont,
            )
            update?.let {
                Text(
                    stringResource(R.string.pip_update_available, it),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        } else {
            if (update != null) {
                TextButton(onClick = onUpgrade, enabled = enabled) { Text(stringResource(R.string.pip_upgrade)) }
            }
            IconButton(onClick = onUninstall, enabled = enabled) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.pip_uninstall),
                    tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(details: DetailsState, state: PipState, viewModel: PipViewModel) {
    val uriHandler = LocalUriHandler.current
    val pkg = details.pkg
    val update = state.updateFor(pkg)
    ModalBottomSheet(onDismissRequest = viewModel::closeDetails) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                pkg.name,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = CodeFont,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.pip_version, details.info?.version ?: pkg.version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            update?.let {
                Text(
                    stringResource(R.string.pip_update_available, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (details.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            details.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontFamily = CodeFont)
            }
            details.info?.let { info ->
                info.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                info.license?.let { DetailLine(stringResource(R.string.pip_license, it)) }
                DetailLine(stringResource(R.string.pip_details_requires, info.requires.joinOrNone()))
                DetailLine(stringResource(R.string.pip_details_required_by, info.requiredBy.joinOrNone()))
                info.homePage?.let { url ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                        Text(stringResource(R.string.pip_home_page))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (update != null) {
                    Button(
                        colors = brandButtonColors(),
                        onClick = {
                            viewModel.closeDetails()
                            viewModel.upgrade(pkg)
                        },
                        enabled = !state.installing,
                    ) { Text(stringResource(R.string.pip_upgrade)) }
                }
                OutlinedButton(
                    onClick = { viewModel.requestUninstall(pkg) },
                    enabled = !state.installing && state.uninstalling == null,
                ) { Text(stringResource(R.string.pip_uninstall), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun List<String>.joinOrNone(): String =
    if (isEmpty()) stringResource(R.string.pip_details_none) else joinToString(", ")

@Composable
private fun DetailLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// --- requirements.txt --------------------------------------------------------------------------

@Composable
private fun RequirementsTab(state: PipState, viewModel: PipViewModel, currentFile: File?, projectsRoot: File?) {
    val openFile = remember(currentFile) { currentFile?.takeIf { RequirementsFiles.isCandidate(it) } }
    Column(Modifier.fillMaxSize()) {
        if (state.scanningFiles) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.pip_req_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (openFile != null) {
                item(key = "open-label") { SectionTitle(stringResource(R.string.pip_req_open_file)) }
                item(key = "open-file") {
                    FileRow(openFile, projectsRoot, enabled = !state.installing) { viewModel.installRequirements(openFile) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item(key = "files-label") { SectionTitle(stringResource(R.string.pip_req_project_files)) }
            if (state.requirementFiles.isEmpty() && !state.scanningFiles) {
                item(key = "files-none") {
                    Text(
                        stringResource(R.string.pip_req_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.requirementFiles, key = { "f-" + it.path }) { file ->
                FileRow(file, projectsRoot, enabled = !state.installing) { viewModel.installRequirements(file) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun FileRow(file: File, projectsRoot: File?, enabled: Boolean, onInstall: () -> Unit) {
    val projects = stringResource(R.string.projects_label)
    val where = remember(file, projectsRoot, projects) { displayPath(file, projectsRoot, projects) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, fontFamily = CodeFont, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                where,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onInstall, enabled = enabled) { Text(stringResource(R.string.pip_req_install)) }
    }
}

/** "Projects/app/requirements.txt" for a file under [root], else its absolute path. */
private fun displayPath(file: File, root: File?, rootLabel: String): String {
    if (root != null) {
        val rel = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrNull()
        if (rel != null && !rel.startsWith("..")) return "$rootLabel/$rel"
    }
    return file.absolutePath
}

// --- Operation log -----------------------------------------------------------------------------

@Composable
private fun OperationPanel(state: PipState, onClose: () -> Unit) {
    var showLog by rememberSaveable { mutableStateOf(true) }
    val running = state.running
    val outcome = state.outcome
    Surface(color = SlateBar) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (text, color) = when {
                    running != null -> runningText(running) to MaterialTheme.colorScheme.onSurface
                    outcome is InstallOutcome.Succeeded -> doneText(outcome.operation) to SuccessGreen
                    outcome is InstallOutcome.Failed -> failedText(outcome.operation) to MaterialTheme.colorScheme.error
                    else -> "" to MaterialTheme.colorScheme.onSurface
                }
                when {
                    running != null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    outcome is InstallOutcome.Succeeded -> Icon(Icons.Outlined.CheckCircle, null, tint = color, modifier = Modifier.size(18.dp))
                    outcome is InstallOutcome.Failed -> Icon(Icons.Outlined.Error, null, tint = color, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showLog = !showLog }) {
                    Text(stringResource(if (showLog) R.string.pip_hide_log else R.string.pip_show_log), maxLines = 1)
                }
                if (running == null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
            }
            if (running != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (outcome is InstallOutcome.Failed && outcome.buildFailure) {
                Text(
                    stringResource(R.string.pip_pure_python_hint),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (showLog) {
                InstallLog(
                    state.log,
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp, max = 200.dp)
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InstallLog(log: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
    Box(
        modifier
            .background(Ink, RoundedCornerShape(12.dp))
            .border(1.dp, SlateLine, RoundedCornerShape(12.dp))
            .padding(8.dp)
            .verticalScroll(scroll),
    ) {
        SelectionContainer {
            Text(
                text = log,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = CodeFont,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// --- Texts -------------------------------------------------------------------------------------

private fun PipOperation.target(): String = when (this) {
    is PipOperation.Install -> spec
    is PipOperation.Upgrade -> name
    is PipOperation.Requirements -> file.name
}

@Composable
private fun runningText(op: PipOperation): String = stringResource(
    when (op) {
        is PipOperation.Install -> R.string.pip_running_install
        is PipOperation.Upgrade -> R.string.pip_running_upgrade
        is PipOperation.Requirements -> R.string.pip_running_requirements
    },
    op.target(),
)

@Composable
private fun doneText(op: PipOperation): String = stringResource(
    when (op) {
        is PipOperation.Install -> R.string.pip_installed_spec
        is PipOperation.Upgrade -> R.string.pip_done_upgrade
        is PipOperation.Requirements -> R.string.pip_done_requirements
    },
    op.target(),
)

@Composable
private fun failedText(op: PipOperation): String = stringResource(
    when (op) {
        is PipOperation.Install -> R.string.pip_failed_install
        is PipOperation.Upgrade -> R.string.pip_failed_upgrade
        is PipOperation.Requirements -> R.string.pip_failed_requirements
    },
    op.target(),
)

@Composable
private fun noticeText(notice: PipNotice): String = when (notice) {
    is PipNotice.Done -> doneText(notice.operation)
    is PipNotice.Failed -> failedText(notice.operation)
    is PipNotice.Uninstalled -> stringResource(R.string.pip_uninstalled, notice.name)
}

@StringRes
private fun PipTab.labelRes(): Int = when (this) {
    PipTab.Search -> R.string.pip_tab_search
    PipTab.Installed -> R.string.pip_tab_installed
    PipTab.Requirements -> R.string.pip_tab_requirements
}

@StringRes
private fun SpecProblem.messageRes(): Int = when (this) {
    SpecProblem.Empty -> R.string.pip_spec_empty
    SpecProblem.Spaces -> R.string.pip_spec_spaces
    SpecProblem.Options -> R.string.pip_spec_options
    SpecProblem.Invalid -> R.string.pip_spec_invalid
}
