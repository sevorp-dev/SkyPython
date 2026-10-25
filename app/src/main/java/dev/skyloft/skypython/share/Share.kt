package dev.skyloft.skypython.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.skyloft.skypython.R
import dev.skyloft.skypython.report.AppLog

/**
 * Shares [file] with other apps via `ACTION_SEND` (the system chooser: messengers, mail,
 * drives…) through the app's FileProvider (`<packageName>.files`). The file must live under
 * `filesDir/projects`, `cacheDir/run` or `cacheDir/share` (see `res/xml/file_paths.xml`).
 */
fun shareFile(context: Context, file: File) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, R.string.share_cannot_share, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeTypeFor(file)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.share_chooser_title, file.name)).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.share_no_app, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Open with…: offers [file] to the apps that can view it (`ACTION_VIEW` through the same
 * FileProvider as [shareFile]), always with the chooser so the user picks each time.
 */
fun openWith(context: Context, file: File) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, R.string.share_cannot_share, Toast.LENGTH_SHORT).show()
        return
    }
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeFor(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(view, context.getString(R.string.open_with_chooser_title, file.name)).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_with_no_app, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Shares a whole project folder: packs it into `cacheDir/share/<unique>/<name>.zip` (off the
 * main thread) and sends the archive like a file. Cancelling the caller (the screen went away)
 * stops the packing and leaves no partial archive.
 */
suspend fun shareFolder(context: Context, folder: File) {
    val zip = try {
        withContext(Dispatchers.IO) {
            zipFolder(folder, File(context.cacheDir, "share/zip")) { ensureActive() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ZipTooLargeException) {
        val mb = (e.limitBytes / (1024 * 1024)).toInt()
        Toast.makeText(context, context.getString(R.string.share_folder_too_large, mb), Toast.LENGTH_LONG).show()
        return
    } catch (e: Exception) {
        AppLog.w("Share", "cannot pack ${folder.name}", e)
        Toast.makeText(context, context.getString(R.string.share_folder_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        return
    }
    shareFile(context, zip)
}
