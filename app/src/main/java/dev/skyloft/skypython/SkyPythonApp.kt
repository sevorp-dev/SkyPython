package dev.skyloft.skypython

import android.app.Application
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.skyloft.skypython.console.ConsoleScreen
import dev.skyloft.skypython.console.ConsoleViewModel
import dev.skyloft.skypython.editor.EditorScreen
import dev.skyloft.skypython.editor.EditorViewModel
import dev.skyloft.skypython.files.FilesScreen
import dev.skyloft.skypython.files.FilesViewModel
import dev.skyloft.skypython.files.Workspace
import kotlinx.coroutines.launch
import java.io.File

private object Routes {
    const val EDITOR = "editor"
    const val CONSOLE = "console"
    const val FILES = "files"
}

/**
 * App root. ViewModels are created here, outside the NavHost, so every screen
 * shares the same instances (scoped to the activity).
 */
@Composable
fun SkyPythonApp() {
    val app = LocalContext.current.applicationContext as Application
    // Canonical: filesDir is reached via a symlink (/data/user/0 -> /data/data) and Files lists
    // canonical paths, so the editor must see the same spelling for the same file.
    val workspace = remember { Workspace(File(app.filesDir, "projects").canonicalFile) }

    val editorVm: EditorViewModel = viewModel(
        factory = EditorViewModel.factory(app, workspace.root, workspace::ensureHello),
    )
    val consoleVm: ConsoleViewModel = viewModel(factory = ConsoleViewModel.factory(app, workspace.root))
    val filesVm: FilesViewModel = viewModel(factory = FilesViewModel.Factory(workspace))

    val nav = rememberNavController()
    val scope = rememberCoroutineScope()

    NavHost(navController = nav, startDestination = Routes.EDITOR) {
        composable(Routes.EDITOR) {
            EditorScreen(
                viewModel = editorVm,
                onRun = { file ->
                    // Only one script at a time: a running one makes this ask first.
                    if (consoleVm.requestRun(file)) {
                        nav.navigate(Routes.CONSOLE) { launchSingleTop = true }
                    }
                },
                onOpenFiles = {
                    // Save first so a rename/delete in Files never loses edits.
                    scope.launch {
                        editorVm.saveAll().join()
                        filesVm.refresh()
                        nav.navigate(Routes.FILES) { launchSingleTop = true }
                    }
                },
            )
            RestartScriptDialog(
                consoleVm = consoleVm,
                onConfirmed = { nav.navigate(Routes.CONSOLE) { launchSingleTop = true } },
            )
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
    }
}

/** "Run while running asks to restart" (spec): shown while a Run request is pending. */
@Composable
private fun RestartScriptDialog(consoleVm: ConsoleViewModel, onConfirmed: () -> Unit) {
    val state by consoleVm.state.collectAsStateWithLifecycle()
    val pending = state.pendingRun ?: return
    AlertDialog(
        onDismissRequest = consoleVm::dismissPendingRun,
        text = { Text("A script is still running. Stop it and run ${pending.name}?") },
        confirmButton = {
            TextButton(onClick = {
                consoleVm.confirmPendingRun()
                onConfirmed()
            }) { Text("Stop & run") }
        },
        dismissButton = {
            TextButton(onClick = consoleVm::dismissPendingRun) { Text("Cancel") }
        },
    )
}
