package dev.skyloft.skypython.report

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The report as plain text (Markdown-friendly, so it reads well pasted into a GitHub issue).
 * Always English: it is read by the developers, whatever the app's language.
 */
object ReportFormatter {
    /** App-log lines included in a report. */
    const val LOG_LINES = 300
    const val TITLE_MAX = 100
    const val NOT_PROVIDED = "(not provided)"

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)

    fun format(report: ProblemReport, description: String, info: List<InfoSection>?, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            appendLine("# SkyPython problem report")
            appendLine()
            header(report, zone).forEach { appendLine(it) }
            appendLine()
            appendLine("## What were you doing?")
            appendLine(description.trim().ifEmpty { NOT_PROVIDED })
            appendLine()
            appendLine("## Error")
            val trace = report.stackTrace
            when {
                trace != null -> fenced(trace)
                report.message != null -> appendLine(report.message)
                else -> appendLine("(none: reported by the user)")
            }
            info?.forEach { section ->
                val entries = section.entries.filterNot { Privacy.isForbiddenKey(it.key) }
                if (entries.isEmpty()) return@forEach
                appendLine()
                appendLine("## ${section.title}")
                entries.forEach { appendLine("- ${it.key}: ${it.value}") }
            }
            appendLine()
            val log = report.log.takeLast(LOG_LINES)
            appendLine("## App log (last ${log.size} lines)")
            if (log.isEmpty()) appendLine("(empty)") else fenced(log.joinToString("\n"))
        }.trimEnd() + "\n"

    /** One line for the issue title. */
    fun title(report: ProblemReport): String {
        val message = report.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val text = when (report.kind) {
            ReportKind.Crash -> "Crash: $message"
            ReportKind.Error -> "Error (${report.where ?: "app"}): $message"
            ReportKind.Manual -> if (message.isEmpty()) "Problem report" else message
        }.trim().removeSuffix(":")
        return if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX - 1) + "…"
    }

    /**
     * What must survive a cut link: the header, the first [maxStackLines] lines of the trace and
     * every `Caused by:` line after them.
     */
    fun essentials(report: ProblemReport, maxStackLines: Int = 40, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            header(report, zone).forEach { appendLine(it) }
            val trace = report.stackTrace
            if (trace != null) {
                val lines = trace.lines()
                appendLine()
                lines.take(maxStackLines).forEach { appendLine(it) }
                val causes = lines.drop(maxStackLines).filter { it.startsWith("Caused by:") }
                if (lines.size > maxStackLines) appendLine("\t…")
                causes.forEach { appendLine(it) }
            } else if (report.message != null) {
                appendLine()
                appendLine(report.message)
            }
        }.trimEnd()

    /** `Key: value` lines of every allowed entry, for the issue form's Device field. */
    fun deviceSummary(info: List<InfoSection>): String =
        info.flatMap { it.entries }
            .filterNot { Privacy.isForbiddenKey(it.key) }
            .joinToString("\n") { "${it.key}: ${it.value}" }

    private fun header(report: ProblemReport, zone: ZoneId): List<String> = listOfNotNull(
        "Kind: ${report.kind}",
        "Time: ${TIME.format(Instant.ofEpochMilli(report.timeMillis).atZone(zone))}",
        report.appVersion?.let { "App: $it" },
        report.where?.let { "Where: $it" },
        report.thread?.let { "Thread: $it" },
        report.screen?.let { "Screen: $it" },
    )

    private fun StringBuilder.fenced(text: String) {
        appendLine("```")
        appendLine(text.trimEnd())
        appendLine("```")
    }
}
