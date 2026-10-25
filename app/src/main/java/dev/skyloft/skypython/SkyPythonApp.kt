package dev.skyloft.skypython

import dev.skyloft.skypython.project.OpenProjectScreen
import dev.skyloft.skypython.project.ProjectsViewModel
import dev.skyloft.skypython.project.StoragePermission
import dev.skyloft.skypython.project.StorageRoot
import dev.skyloft.skypython.project.friendlyPath
import dev.skyloft.skypython.project.projectName
import dev.skyloft.skypython.ui.rememberMainScope
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import dev.skyloft.skypython.ui.LocalSnackbarClearance
import dev.skyloft.skypython.ui.SnackbarClearance
import android.app.Application
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import dev.skyloft.skypython.editor.EditorController
import dev.skyloft.skypython.ui.nav.BottomNavPolicy
import dev.skyloft.skypython.ui.nav.SkyBottomBar
import dev.skyloft.skypython.ui.nav.TopLevelDestination
import dev.skyloft.skypython.ui.theme.Slate
import kotlinx.coroutines.flow.map
import dev.skyloft.skypython.about.AboutScreen
import dev.skyloft.skypython.console.ConsoleScreen
import dev.skyloft.skypython.console.ConsoleViewModel
import dev.skyloft.skypython.console.RunTarget
import dev.skyloft.skypython.debug.BreakpointConditionDialog
import dev.skyloft.skypython.debug.DebugPanelHost
import dev.skyloft.skypython.debug.DebugPhase
import dev.skyloft.skypython.debug.DebugViewModel
import dev.skyloft.skypython.debug.rememberDebugMarks
import dev.skyloft.skypython.editor.DocSource
import dev.skyloft.skypython.editor.EditorScreen
import dev.skyloft.skypython.editor.EditorViewModel
import dev.skyloft.skypython.files.FilesScreen
import dev.skyloft.skypython.files.FilesViewModel
import dev.skyloft.skypython.files.Workspace
import dev.skyloft.skypython.files.describeFileError
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.pip.PipScreen
import dev.skyloft.skypython.pip.PipViewModel
import dev.skyloft.skypython.report.CrashPrompt
import dev.skyloft.skypython.report.ErrorBanner
import dev.skyloft.skypython.report.ErrorReporter
import dev.skyloft.skypython.report.ReportScreen
import dev.skyloft.skypython.samples.SamplesScreen
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.settings.SettingsScreen
import dev.skyloft.skypython.settings.SettingsStore
import dev.skyloft.skypython.share.shareFile
import dev.skyloft.skypython.terminal.TerminalKind
import dev.skyloft.skypython.terminal.TerminalScreen
import dev.skyloft.skypython.terminal.TerminalViewModel
import dev.skyloft.skypython.ui.drawer.DrawerAction
import dev.skyloft.skypython.viewer.ImageViewerScreen
import kotlinx.coroutines.launch
import java.io.File

private object Routes {
    const val EDITOR = "editor"
    const val CONSOLE = "console"
    const val RUN = "run"
    const val INTERPRETER = "interpreter"
    const val TERMINAL = "terminal"
    const val FILES = "files"
    const val PIP = "pip"
    const val SETTINGS = "settings"
    const val SAMPLES = "samples"
    const val ABOUT = "about"
    const val REPORT = "report"
    const val REPORT_ID = "id"
    const val IMAGE = "image"
    const val IMAGE_PATH = "path"
    const val OPEN_PROJECT = "open-project"

    /** The image viewer showing [file]. */
    fun image(file: File) = "$IMAGE?$IMAGE_PATH=${android.net.Uri.encode(file.path)}"

    /** The Report screen, with a saved crash or a recent error attached when [id] is given. */
    fun report(id: String? = null) = if (id == null) REPORT else "$REPORT?$REPORT_ID=$id"
}

/**
 * App root. ViewModels are created here, outside the NavHost, so every screen
 * shares the same instances (scoped to the activity).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
// [snackbar]: where problems the app survived are announced; MainActivity draws it above every screen.
fun SkyPythonApp(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    // Canonical: filesDir is reached via a symlink (/data/user/0 -> /data/data) and Files lists
    // canonical paths, so the editor must see the same spelling for the same file.
    val workspace = remember { Workspace(File(app.filesDir, "projects").canonicalFile) }
    val settingsStore = remember { SettingsStore(app) }
    // Null until DataStore has been read: the screens that use it wait, so nothing starts with the
    // defaults and is then undone (the language server with completion off, font sizes jumping).
    val loadedSettings: EditorSettings? by settingsStore.settings.collectAsState(initial = null)

    // The open project: the app's own folder, or one opened from the phone's storage. The others
    // read it when they need it (new files, the interpreter's and terminal's working directory).
    val projectsVm: ProjectsViewModel = viewModel(factory = ProjectsViewModel.factory(app, workspace.root))
    val projectRoot: () -> File = { projectsVm.current.value }
    val project by projectsVm.current.collectAsStateWithLifecycle()
    val editorVm: EditorViewModel = viewModel(
        factory = EditorViewModel.factory(app, workspace.root, workspace::ensureHello, projectRoot),
    )
    val consoleVm: ConsoleViewModel = viewModel(factory = ConsoleViewModel.factory(app, projectRoot))
    val filesVm: FilesViewModel = viewModel(factory = FilesViewModel.Factory(workspace) { describeFileError(AppLocale.localized(app), it) })
    // Both live as long as the activity: leaving the screen keeps the program running.
    val interpreterVm: TerminalViewModel = viewModel(
        key = "terminal-interpreter",
        factory = TerminalViewModel.factory(app, projectRoot, TerminalKind.Interpreter),
    )
    val shellVm: TerminalViewModel = viewModel(
        key = "terminal-shell",
        factory = TerminalViewModel.factory(app, projectRoot, TerminalKind.Shell),
    )
    // ▶ Run: the script on a terminal of its own (real input(), colours, Ctrl+C).
    val runVm: TerminalViewModel = viewModel(
        key = "terminal-run",
        factory = TerminalViewModel.factory(app, projectRoot, TerminalKind.Run),
    )

    val debugVm: DebugViewModel = viewModel(factory = DebugViewModel.factory(app, projectRoot))
    // Activity-scoped so other screens can call pipVm.installNow(...).
    val pipVm: PipViewModel = viewModel(factory = PipViewModel.factory(app, projectRoot))

    // Files follows the open project; the app's own folder is created when missing, others never are.
    LaunchedEffect(project) { filesVm.setRoot(project, create = project.path == workspace.root.path) }
    LaunchedEffect(projectsVm) { projectsVm.messages.collect { snackbar.showSnackbar(it) } }
    val projectName = projectName(project, workspace.root, stringResource(R.string.projects_section_app))

    val nav = rememberNavController()
    val scope = rememberMainScope()
    val openConsole = { nav.go(Routes.CONSOLE) }
    val openRun = { nav.go(Routes.RUN) }
    val openImage: (File) -> Unit = { file -> nav.go(Routes.image(file)) }
    val openInEditor = { file: File, line: Int ->
        editorVm.openAt(file, line)
        nav.popBackStack(Routes.EDITOR, inclusive = false)
        Unit
    }
    // Debugging stays in the editor: the program runs in the console, its panel sits under the code.
    val attachDebugger = { target: RunTarget.Debug -> debugVm.attach(target) { consoleVm.isActive(target) } }
    val startDebug: (File) -> Unit = { file ->
        scope.launch {
            val target = debugVm.prepare(file) ?: return@launch
            if (consoleVm.request(target)) attachDebugger(target)
        }
    }
    val onTargetConfirmed = { target: RunTarget -> if (target is RunTarget.Debug) attachDebugger(target) else openConsole() }
    val debugState by debugVm.state.collectAsStateWithLifecycle()
    val editorState by editorVm.state.collectAsStateWithLifecycle()
    val currentPath = (editorState.current?.source as? DocSource.Local)?.file?.path
    // Paused in (or a frame selected in) another of the user's files: show that file.
    LaunchedEffect(debugState.phase, debugState.currentFrame) {
        val frame = debugState.currentFrame ?: return@LaunchedEffect
        if (debugState.phase == DebugPhase.Paused && frame.user && frame.file != currentPath) {
            val file = File(frame.file)
            if (file.isFile) editorVm.open(file)
        }
    }
    var conditionFor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // Problem reports name the screen the user was on.
    DisposableEffect(nav) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ -> ErrorReporter.onScreen(destination.route) }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
    ErrorBanner(snackbar) { nav.go(Routes.report(it.id)) }
    CrashPrompt { nav.go(Routes.report(it.id)) }

    // Bottom navigation (a setting; the ☰ drawer stays either way). Hidden until the setting is
    // read so it never flashes for someone who turned it off.
    val bottomNavEnabled by remember(settingsStore) { settingsStore.settings.map { it.bottomNavBar } }
        .collectAsState(initial = false)
    val editorController = remember { EditorController() }
    var drawerOpen by remember { mutableStateOf(false) }
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBottomBar = BottomNavPolicy.isVisible(
        enabled = bottomNavEnabled,
        route = route,
        imeVisible = WindowInsets.isImeVisible,
        drawerOpen = drawerOpen,
        debugPanelOpen = debugState.phase != DebugPhase.Idle,
    )
    val barLabels = BottomNavPolicy.showLabels(LocalConfiguration.current.screenHeightDp)
    // A top-level screen: a tab when the bar is on (back returns to the editor, tabs keep their
    // state), a plain push from the editor when it is off (as before the bar existed).
    val goTop: (String) -> Unit = { target -> if (bottomNavEnabled) nav.goTab(target) else nav.go(target) }
    // Save first so a rename/delete in Files never loses edits.
    val openFilesScreen: () -> Unit = {
        scope.launch {
            editorVm.saveAll().join()
            filesVm.refresh()
            goTop(Routes.FILES)
        }
    }
    val openTerminal = {
        shellVm.open()
        goTop(Routes.TERMINAL)
    }
    // Save first: the requirements tab installs from files on disk.
    val openPip: () -> Unit = {
        scope.launch {
            editorVm.saveAll().join()
            goTop(Routes.PIP)
        }
    }
    val onBottomItem: (TopLevelDestination) -> Unit = { destination ->
        if (route == Routes.EDITOR) {
            // Edits reach the ViewModel debounced: push the last keystrokes before leaving.
            editorController.flushPendingText()
            editorController.hideKeyboard()
        }
        when (destination) {
            TopLevelDestination.Editor -> nav.popBackStack(Routes.EDITOR, inclusive = false, saveState = true)
            TopLevelDestination.Terminal -> openTerminal()
            TopLevelDestination.Packages -> openPip()
            TopLevelDestination.Projects -> openFilesScreen()
            TopLevelDestination.Settings -> goTop(Routes.SETTINGS)
        }
    }

    // The editor's symbol bar, the terminal's key row: the problem banner keeps above them.
    val clearance = remember { SnackbarClearance() }
    var hostGapPx by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalSnackbarClearance provides clearance) {
    Column(Modifier.fillMaxSize().background(Slate)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { c ->
                    hostGapPx = (c.findRootCoordinates().size.height - c.positionInRoot().y - c.size.height).roundToInt()
                }
                // The bar below pads itself for the system navigation bar: the screens must not too.
                .then(if (showBottomBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier),
        ) {
            NavHost(navController = nav, startDestination = Routes.EDITOR) {
                composable(Routes.EDITOR) {
                    val settings = loadedSettings ?: return@composable
                    val debugMarks = rememberDebugMarks(
                        debugVm,
                        currentPath,
                        onEditCondition = { path, line -> conditionFor = path to line },
                        onUnsupported = { Toast.makeText(context, R.string.debug_breakpoints_need_project_file, Toast.LENGTH_SHORT).show() },
                    )
                    EditorScreen(
                        viewModel = editorVm,
                        settings = settings,
                        onRun = { file ->
                            // One script at a time: a running one makes this ask first (RunBusyDialog).
                            if (runVm.requestRun(file)) openRun()
                        },
                        onOpenFiles = openFilesScreen,
                        onDebug = startDebug,
                        debugMarks = debugMarks,
                        debugPanel = if (debugState.phase == DebugPhase.Idle) null else { { DebugPanelHost(debugVm, consoleVm) } },
                        onFontSizeChange = { sp -> scope.launch { settingsStore.update { it.copy(fontSizeSp = sp) } } },
                        controller = editorController,
                        onDrawerOpenChange = { drawerOpen = it },
                        projectName = projectName,
                        onDrawerAction = { action ->
                            when (action) {
                                DrawerAction.Projects -> openFilesScreen()
                                // The drawer is only opened from the editor: closing it is all there is to do.
                                DrawerAction.Editor -> Unit
                                DrawerAction.Debug -> scope.launch { editorVm.runCurrent()?.let(startDebug) }
                                DrawerAction.Interpreter -> {
                                    interpreterVm.open()
                                    nav.go(Routes.INTERPRETER)
                                }
                                DrawerAction.Terminal -> openTerminal()
                                DrawerAction.Pip -> openPip()
                                DrawerAction.Samples -> nav.go(Routes.SAMPLES)
                                DrawerAction.Settings -> goTop(Routes.SETTINGS)
                                DrawerAction.Report -> nav.go(Routes.report())
                                DrawerAction.About -> nav.go(Routes.ABOUT)
                                DrawerAction.Share -> scope.launch {
                                    editorVm.saveAll().join()
                                    when (val src = editorVm.state.value.current?.source) {
                                        is DocSource.Local -> shareFile(context, src.file)
                                        is DocSource.External ->
                                            Toast.makeText(context, R.string.share_save_to_projects_first, Toast.LENGTH_SHORT).show()
                                        null -> Toast.makeText(context, R.string.no_file_open, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                    )
                    RunBusyDialog(runVm = runVm, onConfirmed = openRun)
                    RestartDialog(consoleVm = consoleVm, onConfirmed = onTargetConfirmed)
                    conditionFor?.let { (path, line) ->
                        BreakpointConditionDialog(debugVm, path, line, onDismiss = { conditionFor = null })
                    }
                }
                composable(Routes.CONSOLE) {
                    ConsoleScreen(viewModel = consoleVm, onBack = { nav.popBackStack() })
                }
                val saveTerminalFont: (Int) -> Unit = { sp -> scope.launch { settingsStore.update { it.copy(terminalFontSizeSp = sp) } } }
                composable(Routes.RUN) {
                    val settings = loadedSettings ?: return@composable
                    TerminalScreen(
                        viewModel = runVm,
                        onBack = { nav.popBackStack() },
                        // No pseudo-terminal: the same script on the line-mode console over pipes.
                        onUseSimpleConsole = onUseSimpleOutput@{
                            val script = runVm.state.value.script ?: return@onUseSimpleOutput
                            nav.popBackStack()
                            if (consoleVm.requestRun(script)) openConsole()
                        },
                        onOpenInEditor = openInEditor,
                        settings = settings,
                        onFontSizeChange = saveTerminalFont,
                        pip = pipVm,
                        onOpenImage = openImage,
                    )
                }
                // Fallback if the pseudo-terminal can't be opened: the line-mode console over pipes.
                val simpleConsole = { start: () -> Boolean ->
                    nav.popBackStack()
                    if (start()) openConsole()
                    else Toast.makeText(context, R.string.script_still_running, Toast.LENGTH_SHORT).show()
                }
                composable(Routes.INTERPRETER) {
                    val settings = loadedSettings ?: return@composable
                    TerminalScreen(
                        viewModel = interpreterVm,
                        onBack = { nav.popBackStack() },
                        onUseSimpleConsole = { simpleConsole(consoleVm::startInterpreter) },
                        settings = settings,
                        onFontSizeChange = saveTerminalFont,
                        onOpenInEditor = openInEditor,
                    )
                }
                composable(Routes.TERMINAL) {
                    val settings = loadedSettings ?: return@composable
                    TerminalScreen(
                        viewModel = shellVm,
                        onBack = { nav.popBackStack() },
                        onUseSimpleConsole = { simpleConsole(consoleVm::startTerminal) },
                        settings = settings,
                        onFontSizeChange = saveTerminalFont,
                        onOpenInEditor = openInEditor,
                    )
                }
                composable(Routes.FILES) {
                    FilesScreen(
                        viewModel = filesVm,
                        onOpenFile = { file ->
                            editorVm.open(file)
                            nav.popBackStack()
                        },
                        onOpenExternal = { uri ->
                            editorVm.openExternal(uri)
                            nav.popBackStack()
                        },
                        onDeleted = { file ->
                            editorVm.closeFile(file)
                            debugVm.onDeleted(file)
                        },
                        onRenamed = { old, new ->
                            editorVm.onRenamed(old, new)
                            debugVm.store.rename(old.path, new.path)
                        },
                        onBack = { nav.popBackStack() },
                        onOpenImage = openImage,
                        projectName = projectName,
                        projectPath = if (project.path == workspace.root.path) null else {
                            val internal = stringResource(R.string.projects_root_internal)
                            val card = stringResource(R.string.projects_root_sd)
                            remember(project) {
                                friendlyPath(project, StoragePermission.roots(context)) {
                                    if (it.kind == StorageRoot.Kind.Internal) internal else "$card (${it.dir.name})"
                                }
                            }
                        },
                        onOpenProject = { nav.go(Routes.OPEN_PROJECT) },
                        onOpenAppProjects = projectsVm::openPrivate,
                    )
                }
                composable(Routes.OPEN_PROJECT) {
                    OpenProjectScreen(
                        viewModel = projectsVm,
                        onOpened = { nav.popBackStack() },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    "${Routes.IMAGE}?${Routes.IMAGE_PATH}={${Routes.IMAGE_PATH}}",
                    arguments = listOf(navArgument(Routes.IMAGE_PATH) { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    val path = entry.arguments?.getString(Routes.IMAGE_PATH)
                    if (path == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                        return@composable
                    }
                    val file = remember(path) { File(path) }
                    ImageViewerScreen(
                        file = file,
                        onBack = { nav.popBackStack() },
                        onDeleted = {
                            filesVm.refresh()
                            nav.popBackStack()
                        },
                    )
                }
                composable(Routes.PIP) {
                    val editorState by editorVm.state.collectAsStateWithLifecycle()
                    PipScreen(
                        viewModel = pipVm,
                        onBack = { nav.popBackStack() },
                        currentFile = (editorState.current?.source as? DocSource.Local)?.file,
                        projectsRoot = project,
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(store = settingsStore, onBack = { nav.popBackStack() })
                }
                composable(Routes.SAMPLES) {
                    SamplesScreen(
                        projectsRoot = workspace.root,
                        onOpen = { file ->
                            editorVm.open(file)
                            nav.popBackStack(Routes.EDITOR, inclusive = false)
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.ABOUT) {
                    AboutScreen(onBack = { nav.popBackStack() }, onReport = { nav.go(Routes.report()) })
                }
                composable(
                    "${Routes.REPORT}?${Routes.REPORT_ID}={${Routes.REPORT_ID}}",
                    arguments = listOf(navArgument(Routes.REPORT_ID) { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    val settings = loadedSettings ?: return@composable
                    ReportScreen(
                        reportId = entry.arguments?.getString(Routes.REPORT_ID),
                        settings = settings,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            // Snackbars sit above the bottom bar (and above the keyboard), over every screen, and
            // above the screen's own bottom controls.
            val lift = SnackbarClearance.lift(clearance.px, hostGapPx)
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .then(
                        if (lift > 0) Modifier.padding(bottom = with(LocalDensity.current) { lift.toDp() })
                        else Modifier.navigationBarsPadding().imePadding(),
                    ),
            )
        }
        if (showBottomBar) {
            SkyBottomBar(
                current = TopLevelDestination.fromRoute(route),
                showLabels = barLabels,
                onSelect = onBottomItem,
            )
        }
    }
    }
}

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

/**
 * A bottom-bar tab: one at a time above the editor (which is never popped, so it keeps its tabs
 * and state), the left tab's state saved and this one's restored, never stacked twice.
 */
private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(Routes.EDITOR) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** "Only one program at a time": asks before replacing a running script, interpreter or terminal. */
@Composable
private fun RestartDialog(consoleVm: ConsoleViewModel, onConfirmed: (RunTarget) -> Unit) {
    val state by consoleVm.state.collectAsStateWithLifecycle()
    val pending = state.pending ?: return
    AlertDialog(
        onDismissRequest = consoleVm::dismissPendingRun,
        title = { Text(stringResource(R.string.restart_title)) },
        text = { Text(stringResource(R.string.restart_text, runTargetTitle(pending))) },
        confirmButton = {
            TextButton(onClick = {
                consoleVm.confirmPendingRun()
                onConfirmed(pending)
            }) { Text(stringResource(R.string.restart_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = consoleVm::dismissPendingRun) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** "One script at a time" for ▶ Run: asks before replacing the script still running. */
@Composable
private fun RunBusyDialog(runVm: TerminalViewModel, onConfirmed: () -> Unit) {
    val state by runVm.state.collectAsStateWithLifecycle()
    val pending = state.pending ?: return
    AlertDialog(
        onDismissRequest = runVm::dismissPendingRun,
        title = { Text(stringResource(R.string.restart_title)) },
        text = { Text(stringResource(R.string.restart_text, pending.name)) },
        confirmButton = {
            TextButton(onClick = {
                runVm.confirmPendingRun()
                onConfirmed()
            }) { Text(stringResource(R.string.restart_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = runVm::dismissPendingRun) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** What the user sees for [target]: the script's file name, or the localized program name. */
@Composable
fun runTargetTitle(target: RunTarget): String = when (target) {
    is RunTarget.Script -> target.file.name
    is RunTarget.Debug -> target.file.name
    RunTarget.Interpreter -> stringResource(R.string.title_interpreter)
    RunTarget.Terminal -> stringResource(R.string.title_terminal)
}
