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

    /** Report files kept after sharing: the receiving app reads one some time after the chooser closed. */
    const val SHARE_FILES_KEPT = 3

    /**
     * A new file in [dir] for a report being shared, named after [nowMillis] (unique even within
     * a second). Older ones are deleted, all but the newest [SHARE_FILES_KEPT]; the rest goes at
     * the next app start ([cleanUpOnStart]).
     */
    fun newShareFile(dir: File, nowMillis: Long = System.currentTimeMillis()): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
        // Names sort by creation: the time, then a counter within the second.
        val prefix = "skypython-report-$stamp-"
        var n = 1 + dir.list().orEmpty()
            .mapNotNull { it.removePrefix(prefix).takeIf { r -> r != it }?.removeSuffix(".txt")?.toIntOrNull() }
            .fold(0, ::maxOf)
        var file: File
        do {
            file = File(dir, String.format(Locale.US, "%s%03d.txt", prefix, n++))
        } while (!file.createNewFile())
        dir.listFiles { f -> f.isFile && f != file }.orEmpty()
            .sortedByDescending { it.name }
            .drop(SHARE_FILES_KEPT - 1)
            .forEach { it.delete() }
        return file
    }

    /**
     * Once per app start, in the background: shared report files from the last session (their
     * receivers have long read them) and half-written crash reports go.
     */
    fun cleanUpOnStart(context: Context) {
        val app = context.applicationContext
        Thread({
            try {
                File(app.cacheDir, "reports").listFiles().orEmpty().forEach { it.delete() }
                store(app).removeLeftovers()
            } catch (e: Exception) {
                AppLog.w("Reports", "clean-up failed", e)
            }
        }, "report-cleanup").apply { isDaemon = true }.start()
    }

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
        newShareFile(File(context.cacheDir, "reports")).apply { writeText(text) }
    } catch (e: IOException) {
        AppLog.w("Reports", "cannot write the report file", e)
        null
    }
}
