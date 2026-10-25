package dev.skyloft.skypython.report

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Problems the app survived (a runtime that failed to install, a terminal that could not open…):
 * each becomes the current [notice], shown as a banner with a "Report" action, and stays in
 * [recent] for the Report screen. The same problem from the same place is announced once per
 * [REPEAT_WINDOW_MS]. Kept in memory only: nothing is saved or sent.
 */
class ErrorHub(
    private val now: () -> Long = System::currentTimeMillis,
    private val logTail: () -> List<String> = { AppLog.tail() },
    private val screen: () -> String? = { null },
    private val appVersion: () -> String? = { null },
) {
    private val _notice = MutableStateFlow<ProblemReport?>(null)
    val notice: StateFlow<ProblemReport?> = _notice.asStateFlow()

    private val reports = ArrayDeque<ProblemReport>()
    private val lastSeen = HashMap<String, Long>()

    fun report(where: String, error: Throwable): ProblemReport? =
        add(where, error.toString(), StackTraces.render(error))

    fun report(where: String, message: String): ProblemReport? = add(where, message, null)

    fun dismiss() {
        _notice.value = null
    }

    @Synchronized
    fun find(id: String): ProblemReport? = reports.firstOrNull { it.id == id }

    /** Newest first. */
    @Synchronized
    fun recent(): List<ProblemReport> = reports.toList()

    @Synchronized
    private fun add(where: String, message: String, trace: String?): ProblemReport? {
        val time = now()
        val key = "$where\u0000$message"
        val last = lastSeen[key]
        if (last != null && time - last < REPEAT_WINDOW_MS) return null
        lastSeen[key] = time
        val report = ProblemReport(
            id = PendingReports.newId(),
            kind = ReportKind.Error,
            timeMillis = time,
            where = where,
            thread = Thread.currentThread().name,
            screen = screen(),
            message = message,
            stackTrace = trace,
            appVersion = appVersion(),
            log = logTail(),
        )
        reports.addFirst(report)
        while (reports.size > KEEP) reports.removeLast()
        _notice.value = report
        return report
    }

    companion object {
        const val REPEAT_WINDOW_MS = 30_000L
        const val KEEP = 5
    }
}

/**
 * The app-wide [ErrorHub] and what reports need to know about the app right now.
 *
 * `ErrorReporter.report("terminal", e)` from anywhere: it logs the problem and offers the
 * user to report it. Use it for failures the user can't fix (not for a script's own errors
 * or a missing network).
 */
object ErrorReporter {
    /** Where a problem happened: also picks the banner's wording. */
    object Where {
        const val PYTHON = "python-runtime"
        const val TERMINAL = "terminal"
        const val LANGUAGE_SERVER = "language-server"
        const val DEBUGGER = "debugger"
        const val PIP = "pip"
    }

    /** The navigation route on screen (set by the app's NavHost). */
    @Volatile var screen: String? = null
        private set

    /** The screen before [screen]: where the user was before opening "Report a problem". */
    @Volatile var previousScreen: String? = null
        private set

    fun onScreen(route: String?) {
        val name = route?.substringBefore('?')
        if (name == screen) return
        previousScreen = screen
        screen = name
        AppLog.i("Nav", "screen: $name")
    }

    /** "0.0.3 (32), debug" once the app has started. */
    @Volatile var appVersion: String? = null

    val hub = ErrorHub(screen = { screen }, appVersion = { appVersion })

    fun report(where: String, error: Throwable): ProblemReport? {
        AppLog.e(TAG, "problem in $where", error)
        return hub.report(where, error)
    }

    fun report(where: String, message: String): ProblemReport? {
        AppLog.e(TAG, "problem in $where: $message")
        return hub.report(where, message)
    }

    private const val TAG = "ErrorReporter"
}
