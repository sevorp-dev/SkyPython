package dev.skyloft.skypython.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    @Test
    fun parsesPlainAndTaggedVersions() {
        assertEquals(SemVer(0, 0, 5), SemVer.parse("0.0.5"))
        assertEquals(SemVer(1, 12, 3), SemVer.parse("v1.12.3"))
        assertEquals(SemVer(2, 0, 0), SemVer.parse(" V2.0.0 "))
    }

    @Test
    fun rejectsWhatIsNotXyz() {
        listOf("", "1.2", "1.2.3.4", "1.2.x", "v", "1.2.3-beta", "latest", "-1.2.3", "1..3").forEach {
            assertNull(it, SemVer.parse(it))
        }
    }

    @Test
    fun comparesNumericallyNotAsText() {
        assertTrue(SemVer.parse("0.0.10")!! > SemVer.parse("0.0.9")!!)
        assertTrue(SemVer.parse("0.1.0")!! > SemVer.parse("0.0.99")!!)
        assertTrue(SemVer.parse("1.0.0")!! > SemVer.parse("0.99.99")!!)
        assertEquals(0, SemVer.parse("1.2.3")!!.compareTo(SemVer.parse("v1.2.3")!!))
        assertEquals("1.2.3", SemVer(1, 2, 3).toString())
    }

    @Test
    fun newerOnlyWhenStrictlyGreater() {
        assertTrue(Releases.isNewer("0.0.6", current = "0.0.5"))
        assertFalse(Releases.isNewer("0.0.5", current = "0.0.5"))
        assertFalse(Releases.isNewer("0.0.4", current = "0.0.5"))
        // A version we can't read is never offered.
        assertFalse(Releases.isNewer("nightly", current = "0.0.5"))
        // An odd current version (a local build) still sees real releases.
        assertTrue(Releases.isNewer("0.0.6", current = "dev"))
    }

    @Test
    fun readsTheTagFromTheLatestRedirect() {
        assertEquals("v0.0.4", Releases.tagFromLocation("https://github.com/sevorp-dev/SkyPython/releases/tag/v0.0.4"))
        assertEquals("v1.2.3", Releases.tagFromLocation("/sevorp-dev/SkyPython/releases/tag/v1.2.3"))
        assertEquals("v1.2.3", Releases.tagFromLocation("https://github.com/sevorp-dev/SkyPython/releases/tag/v1.2.3?x=1"))
        assertEquals("v1.2.3", Releases.tagFromLocation("https://github.com/sevorp-dev/SkyPython/releases/tag/v1.2.3/"))
    }

    @Test
    fun noTagWhenTheRedirectIsNotAReleaseTag() {
        // No release yet: GitHub sends the releases list.
        assertNull(Releases.tagFromLocation("https://github.com/sevorp-dev/SkyPython/releases"))
        assertNull(Releases.tagFromLocation(null))
        assertNull(Releases.tagFromLocation(""))
        // Not one of the app's version tags (the CPython builds were tagged like this).
        assertNull(Releases.tagFromLocation("https://github.com/sevorp-dev/SkyPython/releases/tag/python-android-3.14.9"))
        assertNull(Releases.tagFromLocation("https://github.com/sevorp-dev/SkyPython/releases/tag/v1.2.3-rc1"))
    }

    @Test
    fun assetNamesMatchTheReleaseWorkflow() {
        assertEquals("SkyPython-0.0.6-arm64-v8a.apk", Releases.apkName(SemVer(0, 0, 6), "arm64-v8a"))
        assertEquals("SkyPython-0.0.6-armeabi-v7a.apk", Releases.apkName(SemVer(0, 0, 6), "armeabi-v7a"))
        assertEquals("SkyPython-0.0.6-x86.apk", Releases.apkName(SemVer(0, 0, 6), "x86"))
        assertEquals("SkyPython-0.0.6-x86_64.apk", Releases.apkName(SemVer(0, 0, 6), "x86_64"))
        assertEquals("SkyPython-0.0.6-universal.apk", Releases.apkName(SemVer(0, 0, 6), "universal"))
    }

    @Test
    fun unknownVariantFallsBackToUniversal() {
        assertEquals("SkyPython-0.0.6-universal.apk", Releases.apkName(SemVer(0, 0, 6), "mips"))
        assertEquals("SkyPython-0.0.6-universal.apk", Releases.apkName(SemVer(0, 0, 6), ""))
    }

    @Test
    fun urls() {
        val v = SemVer(0, 0, 6)
        assertEquals("https://github.com/sevorp-dev/SkyPython/releases/latest", Releases.LATEST_URL)
        assertEquals(
            "https://github.com/sevorp-dev/SkyPython/releases/download/v0.0.6/SkyPython-0.0.6-x86_64.apk",
            Releases.apkUrl(v, "x86_64"),
        )
        assertEquals("https://github.com/sevorp-dev/SkyPython/releases/download/v0.0.6/SHA256SUMS", Releases.sumsUrl(v))
        assertEquals(
            "https://raw.githubusercontent.com/sevorp-dev/SkyPython/v0.0.6/.github/release-notes/v0.0.6.md",
            Releases.notesUrl(v),
        )
        assertEquals("https://github.com/sevorp-dev/SkyPython/releases/tag/v0.0.6", Releases.pageUrl(v))
    }
}

class Sha256SumsTest {
    private val a = "9f6c089166a56e2afade389a88fad7add4b51a4196330afe74090c8c75038d66"
    private val b = "ACB20172AD74459DF3C5AA7032297657DA32B378DF52297B3F8E8D378060C85E"

    @Test
    fun readsSha256sumOutput() {
        val sums = Sha256Sums.parse(
            """
            $a  SkyPython-0.0.4-arm64-v8a.apk
            $b *SkyPython-0.0.4-x86_64.apk
            """.trimIndent() + "\r\n",
        )
        assertEquals(a, sums["SkyPython-0.0.4-arm64-v8a.apk"])
        // Binary-mode marker dropped, hex lowercased.
        assertEquals(b.lowercase(), sums["SkyPython-0.0.4-x86_64.apk"])
        assertEquals(2, sums.size)
    }

    @Test
    fun skipsJunkLines() {
        val sums = Sha256Sums.parse("# comment\n\nnot-a-hash  file.apk\n${a.take(63)}  short.apk\n$a  ok.apk\n$a  dir/evil.apk\n")
        assertEquals(mapOf("ok.apk" to a), sums)
    }

    @Test
    fun emptyText() {
        assertTrue(Sha256Sums.parse("").isEmpty())
    }
}
