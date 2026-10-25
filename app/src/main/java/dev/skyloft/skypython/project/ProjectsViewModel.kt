package dev.skyloft.skypython.project

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.skyloft.skypython.R
import dev.skyloft.skypython.locale.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Context.projectsDataStore by preferencesDataStore(name = "projects")
private val CURRENT_KEY = stringPreferencesKey("current")
private val RECENT_KEY = stringPreferencesKey("recent")

/**
 * The open project: the folder Files shows, new files go to and the interpreter and terminal
 * start in. Either the app's own [privateRoot] (`filesDir/projects`) or any folder in shared
 * storage, which needs storage access ([StoragePermission]). Remembers recent projects.
 */
class ProjectsViewModel(app: Application, val privateRoot: File) : AndroidViewModel(app) {

    private val store = app.projectsDataStore

    private val _current = MutableStateFlow(privateRoot)
    /** The project folder (canonical path). */
    val current: StateFlow<File> = _current.asStateFlow()

    val recents: StateFlow<List<RecentProject>> = store.data
        .map { RecentProjectsCodec.decode(it[RECENT_KEY]) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _access = MutableStateFlow(StoragePermission.granted(app))
    /** Whether shared storage may be used; re-read with [refreshAccess] (e.g. back from Settings). */
    val access: StateFlow<Boolean> = _access.asStateFlow()

    private val _messages = Channel<String>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** One-shot messages (e.g. the last project could not be reopened). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    val isPrivate: Boolean get() = _current.value.path == privateRoot.path

    init {
        viewModelScope.launch {
            val saved = runCatching { store.data.first()[CURRENT_KEY] }.getOrNull()
            val folders = appFolders()
            val startup = withContext(Dispatchers.IO) {
                ProjectCheck.startup(saved, privateRoot, { StorageAccess.needsPermission(it, folders) }, _access.value)
            }
            _current.value = startup.root
            val failed = startup.failed
            val problem = startup.problem
            if (failed != null && problem != null) {
                _messages.trySend(str(R.string.project_fallback, describe(problem, failed)))
            }
        }
    }

    fun refreshAccess() {
        _access.value = StoragePermission.granted(getApplication())
    }

    /** True when [dir] is in shared storage, so opening it needs [access]. */
    fun needsPermission(dir: File): Boolean = StorageAccess.needsPermission(dir, appFolders())

    fun roots(): List<StorageRoot> = StoragePermission.roots(getApplication())

    /** Makes [dir] the project. [onResult] gets null on success or why it can't be opened. */
    fun open(dir: File, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val folder = withContext(Dispatchers.IO) { runCatching { dir.canonicalFile }.getOrDefault(dir.absoluteFile) }
            if (folder.path == privateRoot.path) {
                openPrivate()
                onResult(null)
                return@launch
            }
            refreshAccess()
            val problem = if (needsPermission(folder) && !_access.value) {
                ProjectCheck.Problem.NoPermission
            } else {
                withContext(Dispatchers.IO) { runCatching { ProjectCheck.problem(folder) }.getOrDefault(ProjectCheck.Problem.Unreadable) }
            }
            if (problem != null) {
                onResult(describe(problem, folder))
                return@launch
            }
            _current.value = folder
            runCatching {
                store.edit {
                    it[CURRENT_KEY] = folder.path
                    it[RECENT_KEY] = RecentProjectsCodec.encode(
                        pushRecentProject(RecentProjectsCodec.decode(it[RECENT_KEY]), folder.path, System.currentTimeMillis()),
                    )
                }
            }
            onResult(null)
        }
    }

    /** Back to the app's own projects folder. */
    fun openPrivate() {
        _current.value = privateRoot
        viewModelScope.launch { runCatching { store.edit { it.remove(CURRENT_KEY) } } }
    }

    /** Removes [path] from the recent projects (the folder itself is untouched). */
    fun forget(path: String) {
        viewModelScope.launch {
            runCatching {
                store.edit { it[RECENT_KEY] = RecentProjectsCodec.encode(forgetRecentProject(RecentProjectsCodec.decode(it[RECENT_KEY]), path)) }
            }
        }
    }

    /** The current project was renamed or moved in Files: follow it. */
    fun onMoved(old: File, new: File) {
        val cur = _current.value
        if (cur.path == old.path) open(new)
    }

    fun describe(problem: ProjectCheck.Problem, dir: File): String = str(problem.messageRes(), dir.name.ifEmpty { dir.path })

    private fun appFolders(): List<File> = StoragePermission.appFolders(getApplication())

    private fun str(@StringRes id: Int, vararg args: Any): String = AppLocale.string(getApplication(), id, *args)

    companion object {
        fun factory(app: Application, privateRoot: File): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProjectsViewModel(app, privateRoot) }
        }
    }
}

@StringRes
fun ProjectCheck.Problem.messageRes(): Int = when (this) {
    ProjectCheck.Problem.Missing -> R.string.project_missing
    ProjectCheck.Problem.NotAFolder -> R.string.project_not_folder
    ProjectCheck.Problem.NoPermission -> R.string.project_no_permission
    ProjectCheck.Problem.Unreadable -> R.string.project_unreadable
}
