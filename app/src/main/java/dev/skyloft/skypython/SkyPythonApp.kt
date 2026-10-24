package dev.skyloft.skypython

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.skyloft.skypython.console.ConsoleScreen
import dev.skyloft.skypython.console.ConsoleViewModel
import dev.skyloft.skypython.editor.DocSource
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
    val workspace = remember { Workspace(File(app.filesDir, "projects")) }

    val editorVm: EditorViewModel = viewModel(
        factory = EditorViewModel.factory(app, workspace.root, workspace::ensureHello),
    )
    val consoleVm: ConsoleViewModel = viewModel()
    val filesVm: FilesViewModel = viewModel(factory = FilesViewModel.Factory(workspace))

    val nav = rememberNavController()
    val scope = rememberCoroutineScope()

    NavHost(navController = nav, startDestination = Routes.EDITOR) {
        composable(Routes.EDITOR) {
            EditorScreen(
                viewModel = editorVm,
                onRun = { file ->
                    consoleVm.run(file)
                    nav.navigate(Routes.CONSOLE) { launchSingleTop = true }
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
                onRenamed = { old, new ->
                    val wasOpen = editorVm.state.value.docs.any {
                        (it.source as? DocSource.Local)?.file == old
                    }
                    editorVm.closeFile(old)
                    if (wasOpen) editorVm.open(new)
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
