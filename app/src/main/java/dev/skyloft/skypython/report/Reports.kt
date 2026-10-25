package dev.skyloft.skypython.report

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Wiring of crash reports into the app: the handler, the store and the ways to send a report. */
object Reports {
    /** Longer reports are shared as a .txt file (messengers cut long messages). */
    const val SHARE_AS_FILE_CHARS = 3_500

    fun store(context: Context) = PendingReports(File(context.applicationContext.filesDir, "crash"))

    /** From Application.onCreate (every process of the app): saves crash reports from now on. */
    fun install(context: Context) {
        val app = context.applicationContext
        val version = try {
            DeviceInfo.appVersion(app)
        } catch (e: RuntimeException) {
            null
        }
        ErrorReporter.appVersion = version
        val handler = CrashHandler(
            store = store(app),
            previous = Thread.getDefaultUncaughtExceptionHandler(),
            appVersion = { ErrorReporter.appVersion },
            screen = { ErrorReporter.screen },
        )
        Thread.setDefaultUncaughtExceptionHandler(handler)
        AppLog.i("App", "started $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    /** A new "manual" report: what the user was doing comes from the description they type. */
    fun manual(): ProblemReport = ProblemReport(
        id = PendingReports.newId(),
        kind = ReportKind.Manual,
        timeMillis = System.currentTimeMillis(),
        thread = null,
        screen = ErrorReporter.previousScreen,
        appVersion = ErrorReporter.appVersion,
        log = AppLog.tail(),
    )

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("SkyPython report", text))
    }

    fun issueIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    /**
     * ACTION_SEND for messengers and mail, in a chooser. A long [text] goes as an attached .txt
     * (`cacheDir/reports`, through the FileProvider) with [summary] as the message.
     */
    fun shareIntent(context: Context, text: String, subject: String, summary: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val file = if (text.length > SHARE_AS_FILE_CHARS) writeShareFile(context, text) else null
        if (file != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.putExtra(Intent.EXTRA_TEXT, summary)
            send.clipData = ClipData.newRawUri(file.name, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(send, chooserTitle).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Starts [intent]; false when no app can handle it. */
    fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun writeShareFile(context: Context, text: String): File? = try {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles().orEmpty().forEach { it.delete() } // only the latest one is kept
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir, "skypython-report-$stamp.txt").apply { writeText(text) }
    } catch (e: IOException) {
        AppLog.w("Reports", "cannot write the report file", e)
        null
    }
}
