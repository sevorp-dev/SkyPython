package dev.skyloft.skypython.report

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Crash reports not sent yet (`filesDir/crash/<time>-<id>.json`), newest first, at most [max]:
 * the oldest are dropped. The app asks about a crash once ([unprompted] / [markPrompted]);
 * after that it stays listed on the Report screen until it is deleted or pushed out.
 */
class PendingReports(val dir: File, private val max: Int = MAX) {

    /** Saves [report] synchronously (the crash handler runs right before the process dies). */
    fun add(report: ProblemReport) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        // Locale.US: the names sort by their digits, which must be ASCII on every phone.
        val name = "%015d-%s".format(Locale.US, report.timeMillis, report.id.filter { it.isLetterOrDigit() || it == '-' })
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(gson.toJson(report))
        if (!tmp.renameTo(File(dir, "$name.json"))) throw IOException("Cannot save $name")
        files().drop(max).forEach { it.delete() }
    }

    /** Deletes `.tmp` files of saves cut short (the process died mid-write). Not on the main thread. */
    fun removeLeftovers() {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }.orEmpty().forEach { it.delete() }
    }

    fun list(): List<ProblemReport> = files().mapNotNull(::read).sortedByDescending { it.timeMillis }

    fun get(id: String): ProblemReport? = list().firstOrNull { it.id == id }

    fun remove(id: String) {
        files().filter { read(it)?.id == id }.forEach { it.delete() }
    }

    /** The newest report the user has not been asked about yet. */
    fun unprompted(): ProblemReport? {
        val newest = list().firstOrNull() ?: return null
        val prompted = try {
            File(dir, PROMPTED).readText().trim().toLong()
        } catch (e: IOException) {
            -1L
        } catch (e: NumberFormatException) {
            -1L
        }
        return newest.takeIf { it.timeMillis > prompted }
    }

    /** The user has seen the "closed unexpectedly" question for every saved report. */
    fun markPrompted() {
        val newest = list().firstOrNull() ?: return
        try {
            File(dir, PROMPTED).writeText(newest.timeMillis.toString())
        } catch (e: IOException) {
            AppLog.w(TAG, "cannot save the prompted mark", e)
        }
    }

    /** Newest first (the names start with a zero-padded time). */
    private fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedByDescending { it.name }

    private fun read(file: File): ProblemReport? = try {
        val r = gson.fromJson(file.readText(), ProblemReport::class.java)
        // Gson bypasses Kotlin's defaults and null checks: fill in what an old or hand-edited file lacks.
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
        if (r == null || r.id == null || r.kind == null) null else r.copy(log = r.log ?: emptyList())
    } catch (e: IOException) {
        null
    } catch (e: JsonParseException) {
        null
    }

    companion object {
        const val MAX = 5
        private const val PROMPTED = "prompted"
        private const val TAG = "PendingReports"
        private val gson = Gson()

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * Saves a crash report, then hands the crash to [previous] (the system's handler, which ends
 * the process as usual). Never throws: a failure to save must not hide the original crash.
 */
class CrashHandler(
    private val store: PendingReports,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val now: () -> Long = System::currentTimeMillis,
    private val appVersion: () -> String? = { null },
    private val screen: () -> String? = { null },
    private val logTail: () -> List<String> = { AppLog.tail() },
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            store.add(
                ProblemReport(
                    id = PendingReports.newId(),
                    kind = ReportKind.Crash,
                    timeMillis = now(),
                    thread = thread.name,
                    screen = safe(screen),
                    message = error.toString(),
                    stackTrace = StackTraces.render(error),
                    appVersion = safe(appVersion),
                    log = safe(logTail).orEmpty(),
                ),
            )
        } catch (t: Throwable) {
            // Nothing more to do: the original crash goes on below.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    private fun <T> safe(read: () -> T): T? = try {
        read()
    } catch (t: Throwable) {
        null
    }
}
