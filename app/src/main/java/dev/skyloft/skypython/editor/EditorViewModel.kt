package dev.skyloft.skypython.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.skyloft.skypython.data.SavedTab
import dev.skyloft.skypython.data.Session
import dev.skyloft.skypython.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EditorUiState(
    val docs: List<OpenDocument> = emptyList(),
    val currentId: String? = null,
    /** True until the previous session (or the first-run file) has been loaded. */
    val loading: Boolean = true,
) {
    val current: OpenDocument? get() = docs.firstOrNull { it.id == currentId }
}

/**
 * Open documents, dirty flags, current tab, saving and session restore.
 * All [DocumentsState] mutations happen on the main thread; disk/SAF IO runs on [Dispatchers.IO].
 *
 * @param projectsRoot workspace root (`filesDir/projects`); used for tab labels of same-named files.
 * @param firstRunFile creates (if needed) and returns the file opened on first launch.
 */
class EditorViewModel(
    app: Application,
    projectsRoot: File,
    private val firstRunFile: () -> File,
    private val sessionStore: SessionStore = SessionStore(app),
) : AndroidViewModel(app) {

    private val docsState = DocumentsState()
    /** Resolved once; tab paths are resolved too (see [DocSource.Local]), so labels relativise cleanly. */
    private val projectsRoot: File = resolvedFile(projectsRoot)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** One-shot user-facing messages (e.g. save errors) for a snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val saver = DocumentSaver(
        docs = docsState,
        io = Dispatchers.IO,
        write = { src, text ->
            when (src) {
                is DocSource.Local -> src.file.writeText(text, Charsets.UTF_8)
                is DocSource.External -> writeExternal(src.uri, text)
            }
        },
        onSaved = { publish(persistSession = false) },
        onError = { doc, e -> message("Can't save ${doc.name}: ${e.message}") },
    )
    private var restored = false
    private val sessionToSave = MutableStateFlow<Session?>(null)
    private val resolver get() = getApplication<Application>().contentResolver

    init {
        viewModelScope.launch {
            restore()
            restored = true
            publish()
            sessionToSave.filterNotNull().collectLatest { s ->
                runCatching { sessionStore.save(s) }
            }
        }
    }

    // ---- tabs ----

    fun open(file: File) {
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) { DocSource.Local(file) }
            val id = OpenDocument.idOf(source)
            if (docsState.find(id) != null) {
                docsState.select(id); publish(); return@launch
            }
            val text = withContext(Dispatchers.IO) { runCatching { readLocal(source.file) } }
            text.onSuccess { docsState.open(OpenDocument.of(source, it)); publish() }
                .onFailure { message("Can't open ${file.name}: ${it.message}") }
        }
    }

    fun openExternal(uri: Uri) {
        viewModelScope.launch {
            val existing = docsState.docs.firstOrNull { (it.source as? DocSource.External)?.uri == uri }
            if (existing != null) {
                docsState.select(existing.id); publish(); return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    takePermission(uri)
                    DocSource.External(uri, displayName(uri)) to readExternal(uri)
                }
            }
            result.onSuccess { (src, text) -> docsState.open(OpenDocument.of(src, text)); publish() }
                .onFailure { message("Can't open file: ${it.message}") }
        }
    }

    fun select(id: String) {
        docsState.select(id); publish()
    }

    /** Saves the doc if it has unsaved changes, then closes its tab. */
    fun close(id: String) {
        viewModelScope.launch {
            val doc = docsState.find(id) ?: return@launch
            if (doc.dirty && !save(doc)) return@launch
            docsState.close(id); publish()
        }
    }

    /** Closes tabs of [file] (or of every file under it, for a directory) without saving, e.g. after delete. */
    fun closeFile(file: File) {
        docsState.closeLocal(file); publish()
    }

    /** [old] (file or folder) was renamed to [new] in Files: re-point affected tabs, keeping unsaved text. */
    fun onRenamed(old: File, new: File) {
        docsState.rename(old, new); publish()
    }

    /** Called by the editor when the text changed. */
    fun onTextChanged(id: String, text: String) {
        val before = docsState.find(id) ?: return
        if (before.text == text) return
        docsState.update(id, text)
        publish(persistSession = false)
    }

    // ---- saving / running ----

    fun saveCurrent(): Job = viewModelScope.launch {
        docsState.current?.let { save(it) }
    }

    fun saveAll(): Job = viewModelScope.launch { saveAllNow() }

    /**
     * Saves everything, then returns the file to run for the current tab: the file itself for
     * local docs, a copy in `cacheDir/run/<name>` for external docs (the Python process can't read
     * SAF URIs). Returns null if there is no current doc or saving/copying failed.
     */
    suspend fun runCurrent(): File? {
        if (!saveAllNow()) return null
        val doc = docsState.current ?: return null
        return when (val src = doc.source) {
            is DocSource.Local -> src.file
            is DocSource.External -> withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(getApplication<Application>().cacheDir, "run").apply { mkdirs() }
                    val safeName = src.name.replace('/', '_').ifBlank { "script.py" }
                    File(dir, safeName).apply { writeText(doc.text, Charsets.UTF_8) }
                }
            }.onFailure { message("Can't prepare ${doc.name} to run: ${it.message}") }.getOrNull()
        }
    }

    /** "Save as…": writes the current doc to [uri] (from SAF CreateDocument) and re-targets its tab there. */
    fun saveAs(uri: Uri) {
        viewModelScope.launch {
            val doc = docsState.current ?: return@launch
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    takePermission(uri)
                    writeExternal(uri, doc.text)
                    DocSource.External(uri, displayName(uri))
                }
            }
            result.onSuccess { src ->
                val latest = docsState.find(doc.id) ?: return@onSuccess
                val newDoc = OpenDocument.of(src, latest.text).copy(dirty = latest.text != doc.text)
                docsState.replace(doc.id, newDoc)
                publish()
            }.onFailure { message("Save as failed: ${it.message}") }
        }
    }

    /** Tab label: the file name, or a path relative to the projects root if names clash. */
    fun labelFor(doc: OpenDocument, all: List<OpenDocument>): String = tabLabel(doc, all, projectsRoot)

    private suspend fun saveAllNow(): Boolean = saver.saveAll()

    private suspend fun save(doc: OpenDocument): Boolean = saver.save(doc)

    // ---- session ----

    private suspend fun restore() {
        val session = runCatching { sessionStore.load() }.getOrNull()
        if (session == null) {
            withContext(Dispatchers.IO) {
                runCatching { firstRunFile().let { it to readLocal(it) } }
            }.onSuccess { (file, text) -> docsState.open(OpenDocument.local(file, text)) }
                .onFailure { message("Can't create example file: ${it.message}") }
            return
        }
        val reopened = withContext(Dispatchers.IO) {
            restoreTabs(session.tabs, ::readLocal) { tab ->
                Uri.parse(tab.location).let { OpenDocument.of(DocSource.External(it, tab.name), readExternal(it)) }
            }
        }
        reopened.messages.forEach(::message)
        val loaded = reopened.docs
        loaded.filterNotNull().forEach { docsState.open(it) }
        loaded.getOrNull(session.currentIndex)?.let { docsState.select(it.id) }
    }

    private fun publish(persistSession: Boolean = true) {
        _state.value = EditorUiState(docsState.docs, docsState.currentId, loading = !restored)
        if (persistSession) {
            val docs = docsState.docs
            sessionToSave.value = Session(
                tabs = docs.map { d ->
                    when (val s = d.source) {
                        is DocSource.Local -> SavedTab(SavedTab.Kind.LOCAL, s.file.absolutePath, d.name)
                        is DocSource.External -> SavedTab(SavedTab.Kind.EXTERNAL, s.uri.toString(), d.name)
                    }
                },
                currentIndex = docs.indexOfFirst { it.id == docsState.currentId },
            )
        }
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    // ---- IO helpers (call on Dispatchers.IO) ----

    private fun readLocal(file: File): String {
        require(file.length() <= MAX_FILE_BYTES) { "file is larger than ${MAX_FILE_BYTES / 1_000_000} MB" }
        return file.readText(Charsets.UTF_8)
    }

    private fun readExternal(uri: Uri): String {
        val stream = resolver.openInputStream(uri) ?: error("provider returned no stream")
        val bytes = stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() <= MAX_FILE_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        require(bytes.size <= MAX_FILE_BYTES) { "file is larger than ${MAX_FILE_BYTES / 1_000_000} MB" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeExternal(uri: Uri, text: String) {
        val stream = resolver.openOutputStream(uri, "wt") ?: error("provider returned no stream")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "untitled.py"

    private fun takePermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Not persistable (e.g. a one-shot grant); the doc still works until the grant expires.
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 5_000_000

        fun factory(
            app: Application,
            projectsRoot: File,
            firstRunFile: () -> File,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { EditorViewModel(app, projectsRoot, firstRunFile) }
        }
    }
}
