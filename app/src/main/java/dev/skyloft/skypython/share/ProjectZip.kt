package dev.skyloft.skypython.share

import dev.skyloft.skypython.compat.EntryType
import dev.skyloft.skypython.compat.entryTypeNoFollow
import dev.skyloft.skypython.compat.isSymlink
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Archives kept in the share folder: a receiving app may still be reading an earlier one. */
const val ZIPS_KEPT = 3

/** Projects bigger than this (uncompressed) are not packed: the share would fail or take ages. */
const val MAX_ZIP_BYTES = 200L * 1024 * 1024

/** The folder holds more than [limitBytes] of files. */
class ZipTooLargeException(val limitBytes: Long) : IOException("The folder is larger than $limitBytes bytes")

/**
 * Packs [folder] into `<outDir>/<unique>/<folder name>.zip`, with the folder itself as the
 * top-level directory (so unpacking gives back "my game/…"). Python bytecode caches are left
 * out, and so are symbolic links: made in the terminal, they may point anywhere (up the tree, so
 * the walk would never end, or into the app's private files). Each call makes a new archive;
 * only the newest [ZIPS_KEPT] are kept.
 *
 * Throws [ZipTooLargeException] past [maxBytes]. [checkCancelled] is called as the work goes on
 * and may throw to stop it. A failed or cancelled call leaves nothing behind.
 */
fun zipFolder(
    folder: File,
    outDir: File,
    maxBytes: Long = MAX_ZIP_BYTES,
    checkCancelled: () -> Unit = {},
): File {
    if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("Cannot create $outDir")
    val dir = newArchiveDir(outDir)
    try {
        val zip = File(dir, "${folder.name}.zip")
        val part = File(dir, "${folder.name}.zip.part")
        ZipOutputStream(part.outputStream().buffered()).use { out -> pack(folder, out, maxBytes, checkCancelled) }
        if (!part.renameTo(zip)) throw IOException("Cannot create $zip")
        pruneArchives(outDir, keep = dir)
        return zip
    } catch (t: Throwable) {
        dir.deleteRecursively()
        throw t
    }
}

private fun pack(folder: File, out: ZipOutputStream, maxBytes: Long, checkCancelled: () -> Unit) {
    var total = 0L
    val buf = ByteArray(64 * 1024)
    // Depth first with an explicit stack: a deep tree can't overflow the thread's stack.
    val stack = ArrayDeque<Pair<File, String>>()
    stack.addLast(folder to folder.name)
    while (stack.isNotEmpty()) {
        checkCancelled()
        val (file, path) = stack.removeLast()
        if (file.name == "__pycache__" || file.name.endsWith(".pyc")) continue
        if (file !== folder && isSymlink(file)) continue
        when {
            file === folder || entryTypeNoFollow(file) == EntryType.DIRECTORY -> {
                out.putNextEntry(ZipEntry("$path/"))
                out.closeEntry()
                file.listFiles()?.sortedByDescending { it.name }?.forEach { stack.addLast(it to "$path/${it.name}") }
            }
            file.isFile -> {
                total += file.length()
                if (total > maxBytes) throw ZipTooLargeException(maxBytes)
                out.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        checkCancelled()
                    }
                }
                out.closeEntry()
            }
        }
    }
}

private fun newArchiveDir(outDir: File): File = ShareDirs.newDir(outDir)

private fun pruneArchives(outDir: File, keep: File) = ShareDirs.prune(outDir, keep, ZIPS_KEPT)

/**
 * Files handed to other apps live in folders of their own (`<root>/<unique>/<name>`): the file
 * keeps its plain name for the receiving app, and a new share never overwrites one an app may
 * still be reading. Only the newest few folders are kept.
 */
object ShareDirs {
    private val seq = AtomicLong()

    /** A new, empty folder in [root]; the names sort by creation. */
    fun newDir(root: File): File {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create $root")
        while (true) {
            val name = String.format(Locale.US, "%013d-%06d", System.currentTimeMillis(), seq.incrementAndGet() % 1_000_000)
            val dir = File(root, name)
            if (dir.mkdir()) return dir
            if (!root.isDirectory) throw IOException("Cannot create $dir")
        }
    }

    /** Deletes everything in [root] but [keep] and the newest folders, [count] in all. */
    fun prune(root: File, keep: File, count: Int) {
        val all = root.listFiles().orEmpty().sortedByDescending { it.name }
        val kept = (listOf(keep) + all.filter { it.isDirectory && it != keep }).take(count).toSet()
        all.filter { it !in kept }.forEach { it.deleteRecursively() }
    }
}

/** MIME type for sharing [file], so receiving apps (messengers, mail, drives) treat it right. */
fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
    "py", "pyw" -> "text/x-python"
    "zip" -> "application/zip"
    "txt", "md", "csv", "log", "ini", "cfg", "toml" -> "text/plain"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}
