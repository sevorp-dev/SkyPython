package dev.skyloft.skypython.report

import java.net.URLEncoder

data class IssueLink(val url: String, val truncated: Boolean)

/**
 * A "new issue" link with the bug-report form (`.github/ISSUE_TEMPLATE/bug_report.yml`)
 * prefilled through its field ids. Browsers and GitHub cut very long URLs, so the link is
 * kept under [MAX_URL_LENGTH]: the report field is shortened first (at a line break), then
 * the device field, then the description. The full report goes to the clipboard instead.
 */
object IssueUrl {
    const val NEW_ISSUE = "https://github.com/sevorp-dev/SkyPython/issues/new"
    const val TEMPLATE = "bug_report.yml"
    const val LABEL = "bug"
    const val MAX_URL_LENGTH = 7_000
    const val TRUNCATION_NOTE = "\n… (cut to fit the link: the full report is on the clipboard, paste it here)"
    private const val ELLIPSIS = "…"

    fun build(title: String, description: String, report: String, device: String, maxLength: Int = MAX_URL_LENGTH): IssueLink {
        var fields = Fields(title, description, report, device)
        if (fields.url().length <= maxLength) return IssueLink(fields.url(), truncated = false)

        fields = fields.copy(report = shorten(report, TRUNCATION_NOTE) { fields.copy(report = it).url().length <= maxLength })
        if (fields.url().length > maxLength) {
            fields = fields.copy(device = shorten(device, ELLIPSIS) { fields.copy(device = it).url().length <= maxLength })
        }
        if (fields.url().length > maxLength) {
            fields = fields.copy(description = shorten(description, ELLIPSIS) { fields.copy(description = it).url().length <= maxLength })
        }
        if (fields.url().length > maxLength) {
            fields = fields.copy(title = shorten(title, ELLIPSIS) { fields.copy(title = it).url().length <= maxLength })
        }
        return IssueLink(fields.url(), truncated = true)
    }

    /** Query-string encoding with `%20` for spaces (a `+` is not read as a space everywhere). */
    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private data class Fields(val title: String, val description: String, val report: String, val device: String) {
        fun url(): String = NEW_ISSUE +
            "?template=" + encode(TEMPLATE) +
            "&labels=" + encode(LABEL) +
            "&title=" + encode(title) +
            "&description=" + encode(description) +
            "&report=" + encode(report) +
            "&device=" + encode(device)
    }

    /**
     * The longest prefix of [text] (ending at a line break when it has one) plus [note] that
     * [fits]; just [note] when no prefix does, or "" if not even that fits.
     */
    private fun shorten(text: String, note: String, fits: (String) -> Boolean): String {
        var low = 0
        var high = text.length
        if (!fits(note)) return ""
        // Largest k with fits(text.take(k) + note): the encoded length grows with k.
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (fits(text.take(mid) + note)) low = mid else high = mid - 1
        }
        var kept = text.take(low)
        val lastBreak = kept.lastIndexOf('\n')
        if (lastBreak > 0 && low < text.length && text[low] != '\n') kept = kept.substring(0, lastBreak)
        return kept.trimEnd() + note
    }
}
