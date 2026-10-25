package dev.skyloft.skypython.pip

/** What `pip show <name>` tells about an installed package. */
data class PackageDetails(
    val name: String,
    val version: String,
    val summary: String?,
    val homePage: String?,
    val license: String?,
    val requires: List<String>,
    val requiredBy: List<String>,
)

/** Parses `pip show` (RFC 822-like `Key: value` lines). */
object PipShow {

    private const val MAX_LICENSE_CHARS = 60
    private val FIELD = Regex("""^([A-Za-z][A-Za-z-]*):(?: (.*))?$""")
    private val KNOWN = setOf(
        "Name", "Version", "Summary", "Home-page", "Author", "Author-email", "License",
        "License-Expression", "Location", "Editable project location", "Requires", "Required-by",
    )

    /** Null if there's no `Name:` (e.g. "Package(s) not found"). */
    fun parse(stdout: String): PackageDetails? {
        val fields = mutableMapOf<String, String>()
        for (line in stdout.lines()) {
            val m = FIELD.matchEntire(line.trimEnd('\r')) ?: continue
            val key = m.groupValues[1]
            // A multi-line License text can contain "Something: else" lines; first one wins.
            if (key in KNOWN && key !in fields) fields[key] = m.groupValues[2].trim()
        }
        val name = fields["Name"]?.takeIf { it.isNotEmpty() } ?: return null
        val license = fields["License-Expression"].orNull()
            ?: fields["License"].orNull()?.take(MAX_LICENSE_CHARS)
        return PackageDetails(
            name = name,
            version = fields["Version"].orEmpty(),
            summary = fields["Summary"].orNull(),
            homePage = fields["Home-page"].orNull(),
            license = license,
            requires = fields["Requires"].list(),
            requiredBy = fields["Required-by"].list(),
        )
    }

    private fun String?.orNull(): String? = this?.takeIf { it.isNotEmpty() && it != "UNKNOWN" }

    private fun String?.list(): List<String> =
        orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
