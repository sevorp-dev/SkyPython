package dev.skyloft.skypython.share

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs [folder] into `<outDir>/<folder name>.zip`, with the folder itself as the top-level
 * directory (so unpacking gives back "my game/…"). Python bytecode caches are left out.
 * An older archive with the same name is replaced.
 */
fun zipFolder(folder: File, outDir: File): File {
    if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("Cannot create $outDir")
    val zip = File(outDir, "${folder.name}.zip")
    val part = File(outDir, "${folder.name}.zip.part")
    ZipOutputStream(part.outputStream().buffered()).use { out ->
        fun add(file: File, path: String) {
            if (file.name == "__pycache__" || file.name.endsWith(".pyc")) return
            if (file.isDirectory) {
                out.putNextEntry(ZipEntry("$path/"))
                out.closeEntry()
                file.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$path/${it.name}") }
            } else if (file.isFile) {
                out.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        add(folder, folder.name)
    }
    if (zip.exists() && !zip.delete()) throw IOException("Cannot replace $zip")
    if (!part.renameTo(zip)) throw IOException("Cannot create $zip")
    return zip
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
    else -> "application/octet-stream"
}
