package dev.skyloft.skypython.files

import dev.skyloft.skypython.compat.EntryType
import dev.skyloft.skypython.compat.entryTypeNoFollow
import java.io.File
import java.io.IOException

/** Content of the starter file created by [Workspace.ensureHello]. */
const val HELLO_PY = "# Welcome to SkyPython!\n" +
    "name = input(\"What's your name? \")\n" +
    "print(f\"Hello, {name}! Python is running on your phone.\")\n"

private const val MAX_NAME_BYTES = 255

/**
 * A valid single path segment: non-blank, no path separators (`/`, `\`) or NUL,
 * not `.` / `..`, and at most 255 bytes in UTF-8. Spaces are allowed.
 */
fun isValidName(name: String): Boolean =
    name.isNotBlank() &&
        name != "." && name != ".." &&
        '/' !in name && '\\' !in name && '\u0000' !in name &&
        name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES

/** Why a name is invalid; [english] is the message for logs and tests, the UI localizes it. */
enum class NameProblem(val english: String) {
    Empty("Name must not be empty"),
    Dots("Name must not be \".\" or \"..\""),
    Separator("Name must not contain / or \\"),
    InvalidChar("Name contains an invalid character"),
    TooLong("Name is too long"),
}

/** Why [name] is invalid, or null if it is valid. */
fun nameProblem(name: String): NameProblem? = when {
    name.isBlank() -> NameProblem.Empty
    name == "." || name == ".." -> NameProblem.Dots
    '/' in name || '\\' in name -> NameProblem.Separator
    '\u0000' in name -> NameProblem.InvalidChar
    name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> NameProblem.TooLong
    else -> null
}

/** Human-readable (English) reason why [name] is invalid, or null if it is valid. */
fun nameError(name: String): String? = nameProblem(name)?.english

/** An invalid name was given to a [Workspace] operation. */
class InvalidNameException(val problem: NameProblem) : IllegalArgumentException(problem.english)

/** A [Workspace] operation failed for a known [reason] about the entry called [name]. */
class WorkspaceException(val reason: Reason, val name: String) : IOException(reason.english(name)) {
    enum class Reason(private val english: String) {
        AlreadyExists("\"%s\" already exists"),
        CannotCreateFolder("Could not create folder \"%s\""),
        FolderMissing("Folder does not exist"),
        DoesNotExist("\"%s\" does not exist"),
        CannotRename("Could not rename \"%s\""),
        CannotDelete("Could not delete \"%s\""),
        ;

        fun english(name: String): String = english.replace("%s", name)
    }
}

/**
 * File operations confined to [root] (`filesDir/projects`). Every operation
 * canonicalises its paths and refuses anything that resolves outside [root].
 */
class Workspace(val root: File) {

    companion object {
        /** Message of the IllegalArgumentException thrown for operations on [root] itself. */
        const val ROOT_ERROR = "Cannot modify the workspace root"
    }

    private val rootFile: File get() = root.canonicalFile

    /** True if [file] resolves (canonically) to [root] or something inside it. */
    fun contains(file: File): Boolean = try {
        val path = file.canonicalPath
        val rootPath = rootFile.path
        path == rootPath || path.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
    } catch (e: IOException) {
        false
    }

    private fun isRoot(file: File): Boolean = try {
        file.canonicalFile == rootFile
    } catch (e: IOException) {
        false
    }

    /** Children of [dir]: directories first, then by name (case-insensitive). Empty if [dir] is outside root or missing. */
    fun list(dir: File = root): List<File> {
        if (!contains(dir)) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children.sortedWith(
            compareBy<File> { !it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name },
        )
    }

    fun createFile(dir: File, name: String): Result<File> =
        create(dir, name) { target ->
            if (!target.createNewFile()) throw WorkspaceException(WorkspaceException.Reason.AlreadyExists, name)
        }

    fun createDir(dir: File, name: String): Result<File> =
        create(dir, name) { target ->
            if (target.exists()) throw WorkspaceException(WorkspaceException.Reason.AlreadyExists, name)
            if (!target.mkdir()) throw WorkspaceException(WorkspaceException.Reason.CannotCreateFolder, name)
        }

    private inline fun create(dir: File, name: String, make: (File) -> Unit): Result<File> = runCatching {
        nameProblem(name)?.let { throw InvalidNameException(it) }
        requireInside(dir)
        if (isRoot(dir)) root.mkdirs()
        if (!dir.isDirectory) throw WorkspaceException(WorkspaceException.Reason.FolderMissing, dir.name)
        val target = File(dir.canonicalFile, name)
        requireInside(target)
        make(target)
        target
    }

    /** Renames [file] in place (same parent). Fails if the target name already exists. */
    fun rename(file: File, newName: String): Result<File> = runCatching {
        nameProblem(newName)?.let { throw InvalidNameException(it) }
        val source = entryOf(file)
        if (entryTypeNoFollow(source) == EntryType.MISSING) {
            throw WorkspaceException(WorkspaceException.Reason.DoesNotExist, source.name)
        }
        val target = File(source.parentFile, newName)
        // On case-insensitive filesystems "a.py" -> "A.py" resolves to the same file; allow it.
        if (target.exists() && target.canonicalPath != source.canonicalPath) {
            throw WorkspaceException(WorkspaceException.Reason.AlreadyExists, newName)
        }
        if (!source.renameTo(target)) throw WorkspaceException(WorkspaceException.Reason.CannotRename, source.name)
        target
    }

    /** Deletes [file]; directories are deleted recursively. Symlinks are removed, never followed. */
    fun delete(file: File): Result<Unit> = runCatching {
        val entry = entryOf(file)
        val type = entryTypeNoFollow(entry)
        if (type == EntryType.MISSING) {
            throw WorkspaceException(WorkspaceException.Reason.DoesNotExist, file.name)
        }
        deleteRecursively(entry, type)
    }

    private fun deleteRecursively(entry: File, type: EntryType = entryTypeNoFollow(entry)) {
        if (type == EntryType.DIRECTORY) {
            entry.listFiles()?.forEach { deleteRecursively(it) }
        }
        if (!entry.delete() && entryTypeNoFollow(entry) != EntryType.MISSING) {
            throw WorkspaceException(WorkspaceException.Reason.CannotDelete, entry.name)
        }
    }

    /**
     * Ensures `root/hello.py` exists, creating it with [HELLO_PY] if missing.
     * Never overwrites an existing file. Returns the file.
     */
    fun ensureHello(): File {
        root.mkdirs()
        val hello = File(root, "hello.py")
        if (hello.createNewFile()) hello.writeText(HELLO_PY)
        return hello
    }

    /**
     * The directory entry [file] names, with its parent canonicalised but the last
     * segment kept as-is (so a symlink is the link itself, not its target).
     * Throws unless the entry is strictly inside root.
     */
    private fun entryOf(file: File): File {
        val abs = file.absoluteFile
        val name = abs.name
        val parent = abs.parentFile?.canonicalFile
        if (parent == null || name.isEmpty() || name == "." || name == ".." || !contains(parent)) {
            throw SecurityException("Path is outside the workspace")
        }
        val entry = File(parent, name)
        if (isRoot(entry)) throw IllegalArgumentException(ROOT_ERROR)
        return entry
    }

    private fun requireInside(file: File) {
        if (!contains(file)) throw SecurityException("Path is outside the workspace")
    }
}
