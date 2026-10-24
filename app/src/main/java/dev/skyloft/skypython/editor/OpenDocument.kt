package dev.skyloft.skypython.editor

import android.net.Uri
import java.io.File

/** Where an open document lives. */
sealed interface DocSource {
    /** A file inside the app (normally under the projects root). */
    data class Local(val file: File) : DocSource

    /** A SAF document edited in place through its URI. */
    data class External(val uri: Uri, val name: String) : DocSource
}

data class OpenDocument(
    val id: String,
    val source: DocSource,
    val name: String,
    val text: String,
    val dirty: Boolean,
) {
    companion object {
        fun idOf(source: DocSource): String = when (source) {
            is DocSource.Local -> "file:" + source.file.absolutePath
            is DocSource.External -> "uri:" + source.uri.toString()
        }

        fun of(source: DocSource, text: String): OpenDocument {
            val name = when (source) {
                is DocSource.Local -> source.file.name
                is DocSource.External -> source.name
            }
            return OpenDocument(idOf(source), source, name, text, dirty = false)
        }

        fun local(file: File, text: String): OpenDocument = of(DocSource.Local(file), text)
    }
}

/**
 * Ordered set of open tabs plus the current selection. Pure logic, not thread-safe:
 * EditorViewModel confines it to the main thread.
 */
class DocumentsState {
    var docs: List<OpenDocument> = emptyList()
        private set
    var currentId: String? = null
        private set

    val current: OpenDocument?
        get() = currentId?.let(::find)

    fun find(id: String): OpenDocument? = docs.firstOrNull { it.id == id }

    /** Opens [doc] and selects it. If a doc with the same id is already open, keeps and selects it. */
    fun open(doc: OpenDocument): OpenDocument {
        val existing = find(doc.id)
        if (existing != null) {
            currentId = existing.id
            return existing
        }
        docs = docs + doc
        currentId = doc.id
        return doc
    }

    fun select(id: String) {
        if (find(id) != null) currentId = id
    }

    /** Closes [id]; if it was current, selects the right neighbour, else the left one. */
    fun close(id: String) {
        val index = docs.indexOfFirst { it.id == id }
        if (index < 0) return
        docs = docs.filterIndexed { i, _ -> i != index }
        if (currentId == id) {
            currentId = docs.getOrNull(index)?.id ?: docs.getOrNull(index - 1)?.id
        }
    }

    /** Closes the local doc for [file], and every local doc under it if it is a directory. */
    fun closeLocal(file: File) {
        val path = file.absolutePath
        val prefix = path.trimEnd(File.separatorChar) + File.separator
        docs.filter { d ->
            val src = d.source
            src is DocSource.Local && src.file.absolutePath.let { it == path || it.startsWith(prefix) }
        }.forEach { close(it.id) }
    }

    fun update(id: String, text: String) {
        docs = docs.map { if (it.id == id && it.text != text) it.copy(text = text, dirty = true) else it }
    }

    /**
     * Clears the dirty flag. With [savedText], only clears it if the doc still holds
     * exactly that text (the user may have typed while the save was in flight).
     */
    fun markSaved(id: String, savedText: String? = null) {
        docs = docs.map {
            if (it.id == id && (savedText == null || it.text == savedText)) it.copy(dirty = false) else it
        }
    }

    /** Replaces doc [id] in place (e.g. after "Save as…"), keeping its tab position and selection. */
    fun replace(id: String, newDoc: OpenDocument) {
        val index = docs.indexOfFirst { it.id == id }
        if (index < 0) return
        docs = docs.filterIndexed { i, d -> i == index || d.id != newDoc.id }
            .map { if (it.id == id) newDoc else it }
        if (currentId == id) currentId = newDoc.id
    }

    /** [old] (a file or folder) was renamed to [new]: re-points affected local tabs. */
    fun rename(old: File, new: File) {
        closeLocal(old)
    }
}

/** Tab label: the file name, or a path relative to [projectsRoot] if names clash. */
fun tabLabel(doc: OpenDocument, all: List<OpenDocument>, projectsRoot: File): String {
    val src = doc.source
    if (src !is DocSource.Local || all.count { it.name == doc.name } < 2) return doc.name
    val rel = runCatching { src.file.relativeTo(projectsRoot).path }.getOrNull()
    return if (rel == null || rel.startsWith("..")) src.file.path else rel
}
