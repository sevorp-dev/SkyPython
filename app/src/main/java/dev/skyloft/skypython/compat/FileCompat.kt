package dev.skyloft.skypython.compat

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * True where `java.nio.file` exists: Android 8.0+ (API 26) and the plain JVM that runs unit
 * tests (there `Build.VERSION.SDK_INT` is 0). On Android 7.x (API 24/25) it is missing.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
val hasNioFiles: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || Build.VERSION.SDK_INT == 0

/** What a directory entry itself is, without following a symlink. */
enum class EntryType { MISSING, DIRECTORY, OTHER }

/** The type of the entry [file] names; a symlink is [EntryType.OTHER], never its target. */
fun entryTypeNoFollow(file: File): EntryType =
    if (hasNioFiles) NioEntryType.of(file) else lstatEntryType(file)

@RequiresApi(Build.VERSION_CODES.O)
private object NioEntryType {
    fun of(file: File): EntryType {
        val path = file.toPath()
        return when {
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> EntryType.DIRECTORY
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> EntryType.OTHER
            else -> EntryType.MISSING
        }
    }
}

/** API 24/25: lstat(2) (android.system.Os, API 21+) does not follow symlinks either. */
private fun lstatEntryType(file: File): EntryType = try {
    if (OsConstants.S_ISDIR(Os.lstat(file.path).st_mode)) EntryType.DIRECTORY else EntryType.OTHER
} catch (e: ErrnoException) {
    if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) {
        EntryType.MISSING
    } else {
        throw IOException("Cannot access \"${file.name}\": ${e.message}", e)
    }
}
