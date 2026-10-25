package dev.skyloft.skypython

import android.app.Application
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import dev.skyloft.skypython.samples.SamplesScreen
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.settings.SettingsScreen
import dev.skyloft.skypython.settings.SettingsStore
import dev.skyloft.skypython.share.shareFile
import dev.skyloft.skypython.terminal.TerminalKind
import dev.skyloft.skypython.terminal.TerminalScreen
import dev.skyloft.skypython.terminal.TerminalViewModel
import dev.skyloft.skypython.ui.drawer.DrawerAction
import kotlinx.coroutines.launch
import java.io.File

private object Routes {
    const val EDITOR = "editor"
    const val CONSOLE = "console"
    const val INTERPRETER = "interpreter"
    const val TERMINAL = "terminal"
    const val FILES = "files"
    const val PIP = "pip"
    const val SETTINGS = "settings"
    const val SAMPLES = "samples"
    const val ABOUT = "about"
}

/**
 * App root. ViewModels are created here, outside the NavHost, so every screen
 * shares the same instances (scoped to the activity).
 */
@Composable
fun SkyPythonApp() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    // Canonical: filesDir is reached via a symlink (/data/user/0 -> /data/data) and Files lists
    // canonical paths, so the editor must see the same spelling for the same file.
    val workspace = remember { Workspace(File(app.filesDir, "projects").canonicalFile) }
    val settingsStore = remember { SettingsStore(app) }
    val settings by settingsStore.settings.collectAsState(initial = EditorSettings())

    val editorVm: EditorViewModel = viewModel(
        factory = EditorViewModel.factory(app, workspace.root, workspace::ensureHello),
    )
    val consoleVm: ConsoleViewModel = viewModel(factory = ConsoleViewModel.factory(app, workspace.root))
    val filesVm: FilesViewModel = viewModel(factory = FilesViewModel.Factory(workspace) { describeFileError(AppLocale.localized(app), it) })
    // Both live as long as the activity: leaving the screen keeps the program running.
    val interpreterVm: TerminalViewModel = viewModel(
        key = "terminal-interpreter",
        factory = TerminalViewModel.factory(app, workspace.root, TerminalKind.Interpreter),
    )
    val shellVm: TerminalViewModel = viewModel(
        key = "terminal-shell",
        factory = TerminalViewModel.factory(app, workspace.root, TerminalKind.Shell),
    )

    val debugVm: DebugViewModel = viewModel(factory = DebugViewModel.factory(app, workspace.root))
    // Activity-scoped so other screens can call pipVm.installNow(...).
    val pipVm: PipViewModel = viewModel(factory = PipViewModel.factory(app, workspace.root))

    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val openConsole = { nav.go(Routes.CONSOLE) }
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

    NavHost(navController = nav, startDestination = Routes.EDITOR) {
        composable(Routes.EDITOR) {
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
                    // Only one program at a time: a running one makes this ask first.
                    if (consoleVm.requestRun(file)) openConsole()
                },
                onOpenFiles = {
                    // Save first so a rename/delete in Files never loses edits.
                    scope.launch {
                        editorVm.saveAll().join()
                        filesVm.refresh()
                        nav.go(Routes.FILES)
                    }
                },
                onDebug = startDebug,
                debugMarks = debugMarks,
                debugPanel = if (debugState.phase == DebugPhase.Idle) null else { { DebugPanelHost(debugVm, consoleVm) } },
                onFontSizeChange = { sp -> scope.launch { settingsStore.update { it.copy(fontSizeSp = sp) } } },
                onDrawerAction = { action ->
                    when (action) {
                        DrawerAction.Debug -> scope.launch { editorVm.runCurrent()?.let(startDebug) }
                        DrawerAction.Interpreter -> {
                            interpreterVm.open()
                            nav.go(Routes.INTERPRETER)
                        }
                        DrawerAction.Terminal -> {
                            shellVm.open()
                            nav.go(Routes.TERMINAL)
                        }
                        // Save first: the requirements tab installs from files on disk.
                        DrawerAction.Pip -> scope.launch {
                            editorVm.saveAll().join()
                            nav.go(Routes.PIP)
                        }
                        DrawerAction.Samples -> nav.go(Routes.SAMPLES)
                        DrawerAction.Settings -> nav.go(Routes.SETTINGS)
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
            RestartDialog(consoleVm = consoleVm, onConfirmed = onTargetConfirmed)
            conditionFor?.let { (path, line) ->
                BreakpointConditionDialog(debugVm, path, line, onDismiss = { conditionFor = null })
            }
        }
        composable(Routes.CONSOLE) {
            ConsoleScreen(viewModel = consoleVm, onBack = { nav.popBackStack() })
        }
        // Fallback if the pseudo-terminal can't be opened: the line-mode console over pipes.
        val simpleConsole = { start: () -> Boolean ->
            nav.popBackStack()
            if (start()) openConsole()
            else Toast.makeText(context, R.string.script_still_running, Toast.LENGTH_SHORT).show()
        }
        composable(Routes.INTERPRETER) {
            TerminalScreen(
                viewModel = interpreterVm,
                onBack = { nav.popBackStack() },
                onUseSimpleConsole = { simpleConsole(consoleVm::startInterpreter) },
            )
        }
        composable(Routes.TERMINAL) {
            TerminalScreen(
                viewModel = shellVm,
                onBack = { nav.popBackStack() },
                onUseSimpleConsole = { simpleConsole(consoleVm::startTerminal) },
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
                onDeleted = editorVm::closeFile,
                onRenamed = { old, new ->
                    editorVm.onRenamed(old, new)
                    debugVm.store.rename(old.path, new.path)
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PIP) {
            val editorState by editorVm.state.collectAsStateWithLifecycle()
            PipScreen(
                viewModel = pipVm,
                onBack = { nav.popBackStack() },
                currentFile = (editorState.current?.source as? DocSource.Local)?.file,
                projectsRoot = workspace.root,
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
            AboutScreen(onBack = { nav.popBackStack() })
        }
    }
}

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

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

/** What the user sees for [target]: the script's file name, or the localized program name. */
@Composable
fun runTargetTitle(target: RunTarget): String = when (target) {
    is RunTarget.Script -> target.file.name
    is RunTarget.Debug -> target.file.name
    RunTarget.Interpreter -> stringResource(R.string.title_interpreter)
    RunTarget.Terminal -> stringResource(R.string.title_terminal)
}
