package dev.skyloft.skypython

import android.app.Application
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.skyloft.skypython.about.AboutScreen
import dev.skyloft.skypython.console.ConsoleScreen
import dev.skyloft.skypython.console.ConsoleViewModel
import dev.skyloft.skypython.editor.DocSource
import dev.skyloft.skypython.editor.EditorScreen
import dev.skyloft.skypython.editor.EditorViewModel
import dev.skyloft.skypython.files.FilesScreen
import dev.skyloft.skypython.files.FilesViewModel
import dev.skyloft.skypython.files.Workspace
import dev.skyloft.skypython.pip.PipScreen
import dev.skyloft.skypython.pip.PipViewModel
import dev.skyloft.skypython.samples.SamplesScreen
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.settings.SettingsScreen
import dev.skyloft.skypython.settings.SettingsStore
import dev.skyloft.skypython.share.shareFile
import dev.skyloft.skypython.ui.drawer.DrawerAction
import kotlinx.coroutines.launch
import java.io.File

private object Routes {
    const val EDITOR = "editor"
    const val CONSOLE = "console"
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
    val filesVm: FilesViewModel = viewModel(factory = FilesViewModel.Factory(workspace))

    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val openConsole = { nav.go(Routes.CONSOLE) }

    NavHost(navController = nav, startDestination = Routes.EDITOR) {
        composable(Routes.EDITOR) {
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
                onDrawerAction = { action ->
                    when (action) {
                        DrawerAction.Interpreter -> if (consoleVm.startInterpreter()) openConsole()
                        DrawerAction.Terminal -> if (consoleVm.startTerminal()) openConsole()
                        DrawerAction.Pip -> nav.go(Routes.PIP)
                        DrawerAction.Samples -> nav.go(Routes.SAMPLES)
                        DrawerAction.Settings -> nav.go(Routes.SETTINGS)
                        DrawerAction.About -> nav.go(Routes.ABOUT)
                        DrawerAction.Share -> scope.launch {
                            editorVm.saveAll().join()
                            when (val src = editorVm.state.value.current?.source) {
                                is DocSource.Local -> shareFile(context, src.file)
                                is DocSource.External ->
                                    Toast.makeText(context, "Save the file to Projects to share it", Toast.LENGTH_SHORT).show()
                                null -> Toast.makeText(context, "No file open", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
            RestartDialog(consoleVm = consoleVm, onConfirmed = openConsole)
        }
        composable(Routes.CONSOLE) {
            ConsoleScreen(viewModel = consoleVm, onBack = { nav.popBackStack() })
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
                onRenamed = editorVm::onRenamed,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PIP) {
            PipScreen(viewModel(factory = PipViewModel.factory(app)), onBack = { nav.popBackStack() })
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
private fun RestartDialog(consoleVm: ConsoleViewModel, onConfirmed: () -> Unit) {
    val state by consoleVm.state.collectAsStateWithLifecycle()
    val pending = state.pending ?: return
    AlertDialog(
        onDismissRequest = consoleVm::dismissPendingRun,
        title = { Text("Still running") },
        text = { Text("Something is still running. Stop it and start ${pending.title}?") },
        confirmButton = {
            TextButton(onClick = {
                consoleVm.confirmPendingRun()
                onConfirmed()
            }) { Text("Stop & start") }
        },
        dismissButton = {
            TextButton(onClick = consoleVm::dismissPendingRun) { Text("Cancel") }
        },
    )
}
