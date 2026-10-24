package dev.skyloft.skypython.editor

import android.net.Uri
import dev.skyloft.skypython.compat.hasNioFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real path of [file]: absolute, `.`/`..` removed and symlinks resolved (on Android
 * `/data/user/0` is a symlink to `/data/data`). Works for paths that no longer exist (e.g. the
 * old name after a rename) by resolving the longest existing prefix. Never throws.
 */
fun resolvedFile(file: File): File {
    val canonical = try {
        file.canonicalFile
    } catch (e: IOException) {
        file.absoluteFile
    }
    // File.canonicalFile already resolves symlinks on Unix/Android; toRealPath also does on Windows.
    if (!hasNioFiles) return canonical // Android 7.x: no java.nio.file, canonicalFile is enough
    return try {
        var existing: Path? = canonical.toPath()
        val tail = ArrayDeque<String>()
        while (existing != null && !Files.exists(existing)) {
            tail.addFirst(existing.fileName?.toString() ?: break)
            existing = existing.parent
        }
        if (existing == null) return canonical
        tail.fold(existing.toRealPath()) { p, seg -> p.resolve(seg) }.toFile()
    } catch (e: Exception) {
        canonical
    }
}

/** Where an open document lives. */
sealed interface DocSource {
    /**
     * A file inside the app (normally under the projects root). [file] is always resolved
     * ([resolvedFile]) so one file has one identity however its path was spelled.
     */
    class Local private constructor(val file: File) : DocSource {
        override fun equals(other: Any?): Boolean = other is Local && other.file == file
        override fun hashCode(): Int = file.hashCode()
        override fun toString(): String = "Local(file=$file)"

        companion object {
            operator fun invoke(file: File): Local = Local(resolvedFile(file))
        }
    }

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
            is DocSource.Local -> "file:" + source.file.path
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
        val under = localMatcher(file)
        docs.filter { d -> (d.source as? DocSource.Local)?.let { under(it.file) } == true }
            .forEach { close(it.id) }
    }

    /** Matches [file] itself and, if it is a directory, everything under it (resolved paths). */
    private fun localMatcher(file: File): (File) -> Boolean {
        val path = resolvedFile(file).path
        val prefix = path.trimEnd(File.separatorChar) + File.separator
        return { f -> f.path == path || f.path.startsWith(prefix) }
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

    /**
     * [old] (a file or folder) was renamed to [new]: re-points the local tab of [old] and of every
     * file under it, keeping text, dirty flag, tab position and selection.
     */
    fun rename(old: File, new: File) {
        val oldPath = resolvedFile(old).path
        val newPath = resolvedFile(new).path
        val under = localMatcher(old)
        val renamed = HashMap<String, OpenDocument>()
        for (d in docs) {
            val src = d.source as? DocSource.Local ?: continue
            if (!under(src.file)) continue
            val moved = DocSource.Local(File(newPath + src.file.path.substring(oldPath.length)))
            renamed[d.id] = d.copy(id = OpenDocument.idOf(moved), source = moved, name = moved.file.name)
        }
        if (renamed.isEmpty()) return
        val newIds = renamed.values.mapTo(HashSet()) { it.id }
        // A rename never targets an existing path, but drop any stale tab already holding a new id.
        docs = docs.filter { it.id in renamed || it.id !in newIds }.map { renamed[it.id] ?: it }
        currentId = currentId?.let { renamed[it]?.id ?: it }?.takeIf { find(it) != null } ?: docs.firstOrNull()?.id
    }
}

/** Tab label: the file name, or a path relative to [projectsRoot] if names clash. */
fun tabLabel(doc: OpenDocument, all: List<OpenDocument>, projectsRoot: File): String {
    val src = doc.source
    if (src !is DocSource.Local || all.count { it.name == doc.name } < 2) return doc.name
    val rel = runCatching { src.file.relativeTo(resolvedFile(projectsRoot)).path }.getOrNull()
    return if (rel == null || rel.startsWith("..")) src.file.path else rel
}
