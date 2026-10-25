package dev.skyloft.skypython.report

enum class ReportKind { Crash, Error, Manual }

/**
 * One problem, as saved and shown on the Report screen. Device details are not stored here:
 * they are read when the report is viewed (see [DeviceInfo]).
 *
 * @property where the component for [ReportKind.Error] ("terminal", "pip", …).
 * @property screen the navigation route the user was on.
 * @property log the last lines of [AppLog] at that moment.
 */
data class ProblemReport(
    val id: String,
    val kind: ReportKind,
    val timeMillis: Long,
    val where: String? = null,
    val thread: String? = null,
    val screen: String? = null,
    val message: String? = null,
    val stackTrace: String? = null,
    val appVersion: String? = null,
    val log: List<String> = emptyList(),
)

data class InfoEntry(val key: String, val value: String)

data class InfoSection(val title: String, val entries: List<InfoEntry>)

object StackTraces {
    const val MAX_CHARS = 60_000
    const val CUT_NOTE = "\n… (stack trace cut)"

    /** The full trace with every `Caused by:` and suppressed exception, capped at [maxChars]. */
    fun render(error: Throwable, maxChars: Int = MAX_CHARS): String {
        val full = error.stackTraceToString().trimEnd()
        return if (full.length <= maxChars) full else full.take(maxChars) + CUT_NOTE
    }
}

/** Keys that must never appear in a report, whoever adds the entry. */
object Privacy {
    private val FORBIDDEN = Regex(
        "imei|meid|imsi|iccid|msisdn|phone ?number|line ?1|android ?id|advertis|\\bgaid\\b|serial|" +
            "account|e-?mail|location|latitude|longitude|contact|subscriber|mac address|ssid",
        RegexOption.IGNORE_CASE,
    )

    fun isForbiddenKey(key: String): Boolean = FORBIDDEN.containsMatchIn(key)
}
