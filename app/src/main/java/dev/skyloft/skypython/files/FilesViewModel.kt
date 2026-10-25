package dev.skyloft.skypython.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One row of the file list. `isDirectory` is captured at listing time so the UI never touches the filesystem. */
data class FileEntry(val file: File, val isDirectory: Boolean) {
    val name: String get() = file.name
}

/** One breadcrumb segment; the first one is the workspace root. */
data class Crumb(val label: String, val dir: File)

data class FilesState(
    val currentDir: File,
    val breadcrumbs: List<Crumb> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val canGoUp: Boolean = false,
    val error: String? = null,
)

class FilesViewModel(
    private val workspace: Workspace,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Turns a failed operation into a message for the user (localized in the app). */
    private val describe: (Throwable) -> String = { it.message ?: "Operation failed" },
) : ViewModel() {

    private val root: File = workspace.root.absoluteFile

    private val _state = MutableStateFlow(FilesState(currentDir = root))
    val state: StateFlow<FilesState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    /** Re-lists the current directory (falls back to the nearest existing ancestor inside root). */
    fun refresh() = load(_state.value.currentDir)

    /** Enters [dir] if it is a directory inside the workspace. */
    fun enter(dir: File) = load(dir)

    /** Goes to the parent directory; never above root. */
    fun up() {
        val current = _state.value.currentDir
        if (!_state.value.canGoUp) return
        load(current.parentFile ?: root)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Creates a file in the current directory. [onResult] gets null on success or an error message. */
    fun createFile(name: String, onResult: (String?) -> Unit) =
        mutate(onResult) { workspace.createFile(_state.value.currentDir, name.trim()) }

    /** Creates a folder in the current directory. [onResult] gets null on success or an error message. */
    fun createDir(name: String, onResult: (String?) -> Unit) =
        mutate(onResult) { workspace.createDir(_state.value.currentDir, name.trim()) }

    fun rename(file: File, newName: String, onResult: (String?) -> Unit) =
        mutate(onResult) { workspace.rename(file, newName.trim()) }

    /**
     * Deletes [file] (recursively for folders). [onResult] gets null on success or an
     * error message; a failure is also exposed as [FilesState.error].
     */
    fun delete(file: File, onResult: (String?) -> Unit) =
        mutate(onResult, reportInState = true) { workspace.delete(file) }

    private fun mutate(
        onResult: (String?) -> Unit,
        reportInState: Boolean = false,
        op: () -> Result<*>,
    ) {
        viewModelScope.launch {
            val result = withContext(io) { op() }
            val error = result.exceptionOrNull()?.let(describe)
            if (error != null && reportInState) _state.update { it.copy(error = error) }
            onResult(error)
            load(_state.value.currentDir)
        }
    }

    private fun load(requested: File) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val next = withContext(io) { runCatching { snapshot(requested) } }
            next.onSuccess { snap -> _state.update { snap.copy(error = it.error) } }
                .onFailure { e -> _state.update { it.copy(error = describe(e)) } }
        }
    }

    private fun snapshot(requested: File): FilesState {
        workspace.root.mkdirs()
        val rootCanonical = root.canonicalFile
        var dir = if (workspace.contains(requested)) requested.canonicalFile else rootCanonical
        // The folder may have been deleted or renamed meanwhile: fall back to the nearest existing ancestor.
        while (!dir.isDirectory && dir != rootCanonical) {
            dir = dir.parentFile ?: rootCanonical
        }
        val crumbs = buildList {
            var d: File? = dir
            while (d != null && d != rootCanonical) {
                add(Crumb(d.name, d))
                d = d.parentFile
            }
            add(Crumb(rootCanonical.name.ifEmpty { "projects" }, rootCanonical))
        }.asReversed()
        val entries = workspace.list(dir).map { FileEntry(it, it.isDirectory) }
        return FilesState(
            currentDir = dir,
            breadcrumbs = crumbs,
            entries = entries,
            canGoUp = dir != rootCanonical,
        )
    }

    class Factory(
        private val workspace: Workspace,
        private val describe: (Throwable) -> String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FilesViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return FilesViewModel(workspace, describe = describe) as T
        }
    }
}
