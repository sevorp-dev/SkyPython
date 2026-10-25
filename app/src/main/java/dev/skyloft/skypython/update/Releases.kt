package dev.skyloft.skypython.update

/** A release version `x.y.z` (the tags are `vx.y.z`; no pre-release parts). */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString(): String = "$major.$minor.$patch"

    /** The git tag of this release. */
    val tag: String get() = "v$this"

    companion object {
        private val PATTERN = Regex("""[vV]?(\d{1,6})\.(\d{1,6})\.(\d{1,6})""")

        /** `1.2.3` or `v1.2.3`; null for anything else (pre-releases included). */
        fun parse(text: String?): SemVer? {
            val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val (a, b, c) = m.destructured
            return SemVer(a.toInt(), b.toInt(), c.toInt())
        }
    }
}

/** Where SkyPython's releases live on GitHub, and how the release workflow names their files. */
object Releases {
    private const val REPO = "sevorp-dev/SkyPython"

    /** GitHub answers with a redirect to `.../releases/tag/<latest tag>`; no API rate limit. */
    const val LATEST_URL = "https://github.com/$REPO/releases/latest"

    /** The APK variants the release workflow publishes (.github/workflows/release.yml). */
    val VARIANTS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal")

    private val TAG_IN_PATH = Regex("""/releases/tag/([^/?#]+)/?(?:[?#].*)?$""")

    /** The `vX.Y.Z` tag named by the `/releases/latest` redirect, or null. */
    fun tagFromLocation(location: String?): String? {
        val tag = TAG_IN_PATH.find(location ?: return null)?.groupValues?.get(1) ?: return null
        return SemVer.parse(tag)?.tag?.takeIf { it.equals(tag, ignoreCase = true) }
    }

    /** [latest] is a newer release than [current] (a version that isn't x.y.z counts as 0.0.0). */
    fun isNewer(latest: String, current: String): Boolean {
        val l = SemVer.parse(latest) ?: return false
        return l > (SemVer.parse(current) ?: SemVer(0, 0, 0))
    }

    /** `SkyPython-0.0.6-arm64-v8a.apk`; an unknown [variant] gets the universal APK. */
    fun apkName(version: SemVer, variant: String): String {
        val v = variant.takeIf { it in VARIANTS } ?: "universal"
        return "SkyPython-$version-$v.apk"
    }

    private fun download(version: SemVer, file: String) = "https://github.com/$REPO/releases/download/${version.tag}/$file"

    fun apkUrl(version: SemVer, variant: String): String = download(version, apkName(version, variant))

    fun sumsUrl(version: SemVer): String = download(version, "SHA256SUMS")

    fun notesUrl(version: SemVer): String =
        "https://raw.githubusercontent.com/$REPO/${version.tag}/.github/release-notes/${version.tag}.md"

    fun pageUrl(version: SemVer): String = "https://github.com/$REPO/releases/tag/${version.tag}"
}

/** The `SHA256SUMS` file of a release (`sha256sum *.apk` output). */
object Sha256Sums {
    private val LINE = Regex("""^([0-9a-fA-F]{64}) [ *]([^/\\]+)$""")

    /** File name -> lowercase hex digest; lines that aren't `<digest>  <name>` are skipped. */
    fun parse(text: String): Map<String, String> = text.lineSequence()
        .map { it.trimEnd('\r', ' ') }
        .mapNotNull { LINE.matchEntire(it) }
        .associate { it.groupValues[2] to it.groupValues[1].lowercase() }
}

/** When to check, and what to show. */
object UpdatePolicy {
    const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    fun shouldAutoCheck(enabled: Boolean, lastCheckAt: Long?, now: Long): Boolean {
        if (!enabled) return false
        if (lastCheckAt == null || lastCheckAt > now) return true
        return now - lastCheckAt >= AUTO_CHECK_INTERVAL_MS
    }

    /** Not the version the user chose to skip (badge, banner). */
    fun shouldAnnounce(version: SemVer, skipped: String?): Boolean = SemVer.parse(skipped) != version

    /** The banner, closed with ✕, stays closed for that version. */
    fun showBanner(version: SemVer, dismissed: String?): Boolean = SemVer.parse(dismissed) != version
}
