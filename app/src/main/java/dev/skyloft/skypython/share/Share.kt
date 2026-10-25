package dev.skyloft.skypython.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import dev.skyloft.skypython.R

/**
 * Shares [file] with other apps via `ACTION_SEND` (`text/x-python`) through the app's
 * FileProvider (`<packageName>.files`). The file must live under `filesDir/projects`
 * or `cacheDir/run` (see `res/xml/file_paths.xml`).
 */
fun shareFile(context: Context, file: File) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, R.string.share_cannot_share, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/x-python"
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
