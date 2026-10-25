package dev.skyloft.skypython.project

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/** Android side of [StorageAccess]: reads the grant and opens the right system screen. */
object StoragePermission {

    /** The runtime permissions asked for on Android 7–10. */
    val RUNTIME_PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    val request: StorageAccess.Request get() = StorageAccess.requestFor(Build.VERSION.SDK_INT)

    fun granted(context: Context): Boolean = StorageAccess.granted(
        sdk = Build.VERSION.SDK_INT,
        allFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager(),
        writePermission = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED,
    )

    /** Android 11+: this app's "All files access" page (or the list of apps, if a ROM lacks the page). */
    fun openAllFilesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
        try {
            context.startActivity(own)
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    /** The app's details page, for a permission the user denied for good. */
    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }

    /** Folders the app may always use: its private storage and its app-specific external folders. */
    fun appFolders(context: Context): List<File> = buildList {
        context.filesDir.parentFile?.let(::add)
        add(context.filesDir)
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir -> dir.parentFile?.let(::add) }
    }.map { runCatching { it.canonicalFile }.getOrDefault(it) }

    /** Internal storage and mounted memory cards; never empty while shared storage is mounted. */
    fun roots(context: Context): List<StorageRoot> {
        val fromDirs = StorageRoots.fromAppDirs(runCatching { context.getExternalFilesDirs(null).toList() }.getOrDefault(emptyList()))
        if (fromDirs.isNotEmpty()) return fromDirs
        @Suppress("DEPRECATION")
        return listOf(StorageRoot(Environment.getExternalStorageDirectory(), StorageRoot.Kind.Internal))
    }
}
