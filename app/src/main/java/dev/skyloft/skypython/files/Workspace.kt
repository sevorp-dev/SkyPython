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

/** Human-readable reason why [name] is invalid, or null if it is valid. */
fun nameError(name: String): String? = when {
    name.isBlank() -> "Name must not be empty"
    name == "." || name == ".." -> "Name must not be \".\" or \"..\""
    '/' in name || '\\' in name -> "Name must not contain / or \\"
    '\u0000' in name -> "Name contains an invalid character"
    name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> "Name is too long"
    else -> null
}

/**
 * File operations confined to [root] (`filesDir/projects`). Every operation
 * canonicalises its paths and refuses anything that resolves outside [root].
 */
class Workspace(val root: File) {

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
            if (!target.createNewFile()) throw IOException("\"$name\" already exists")
        }

    fun createDir(dir: File, name: String): Result<File> =
        create(dir, name) { target ->
            if (target.exists()) throw IOException("\"$name\" already exists")
            if (!target.mkdir()) throw IOException("Could not create folder \"$name\"")
        }

    private inline fun create(dir: File, name: String, make: (File) -> Unit): Result<File> = runCatching {
        nameError(name)?.let { throw IllegalArgumentException(it) }
        requireInside(dir)
        if (isRoot(dir)) root.mkdirs()
        if (!dir.isDirectory) throw IOException("Folder does not exist")
        val target = File(dir.canonicalFile, name)
        requireInside(target)
        make(target)
        target
    }

    /** Renames [file] in place (same parent). Fails if the target name already exists. */
    fun rename(file: File, newName: String): Result<File> = runCatching {
        nameError(newName)?.let { throw IllegalArgumentException(it) }
        val source = entryOf(file)
        if (entryTypeNoFollow(source) == EntryType.MISSING) {
            throw IOException("\"${source.name}\" does not exist")
        }
        val target = File(source.parentFile, newName)
        // On case-insensitive filesystems "a.py" -> "A.py" resolves to the same file; allow it.
        if (target.exists() && target.canonicalPath != source.canonicalPath) {
            throw IOException("\"$newName\" already exists")
        }
        if (!source.renameTo(target)) throw IOException("Could not rename \"${source.name}\"")
        target
    }

    /** Deletes [file]; directories are deleted recursively. Symlinks are removed, never followed. */
    fun delete(file: File): Result<Unit> = runCatching {
        val entry = entryOf(file)
        val type = entryTypeNoFollow(entry)
        if (type == EntryType.MISSING) {
            throw IOException("\"${file.name}\" does not exist")
        }
        deleteRecursively(entry, type)
    }

    private fun deleteRecursively(entry: File, type: EntryType = entryTypeNoFollow(entry)) {
        if (type == EntryType.DIRECTORY) {
            entry.listFiles()?.forEach { deleteRecursively(it) }
        }
        if (!entry.delete() && entryTypeNoFollow(entry) != EntryType.MISSING) {
            throw IOException("Could not delete \"${entry.name}\"")
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
        if (isRoot(entry)) throw IllegalArgumentException("Cannot modify the workspace root")
        return entry
    }

    private fun requireInside(file: File) {
        if (!contains(file)) throw SecurityException("Path is outside the workspace")
    }
}
