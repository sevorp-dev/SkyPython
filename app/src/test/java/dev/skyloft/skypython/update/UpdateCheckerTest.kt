package dev.skyloft.skypython.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val http = FakeUpdateHttp()
    private fun checker(current: String = "0.0.5") = UpdateChecker(http, current, Dispatchers.Unconfined)

    private fun latestIs(tag: String) {
        http.heads[Releases.LATEST_URL] = 302 to "https://github.com/sevorp-dev/SkyPython/releases/tag/$tag"
    }

    @Test
    fun newerReleaseWithNotes() = runTest {
        latestIs("v0.0.6")
        http.files[Releases.notesUrl(SemVer(0, 0, 6))] = "# SkyPython 0.0.6\n- Updates\n".toByteArray()
        val result = checker().check()
        assertEquals(CheckResult.Available(SemVer(0, 0, 6), "# SkyPython 0.0.6\n- Updates\n"), result)
    }

    @Test
    fun notesAreOptional() = runTest {
        latestIs("v0.0.6")
        assertEquals(CheckResult.Available(SemVer(0, 0, 6), null), checker().check())
    }

    @Test
    fun sameOrOlderIsUpToDate() = runTest {
        latestIs("v0.0.5")
        assertEquals(CheckResult.UpToDate(SemVer(0, 0, 5)), checker().check())
        latestIs("v0.0.4")
        assertEquals(CheckResult.UpToDate(SemVer(0, 0, 4)), checker().check())
        // The notes are only read for a newer version.
        assertTrue(http.requests.none { it.first.endsWith(".md") })
    }

    @Test
    fun offlineIsAFailure() = runTest {
        http.failing += Releases.LATEST_URL
        assertTrue(checker().check() is CheckResult.Failed)
    }

    @Test
    fun noRedirectOrStrangeTagIsAFailure() = runTest {
        http.heads[Releases.LATEST_URL] = 200 to null
        assertTrue(checker().check() is CheckResult.Failed)
        http.heads[Releases.LATEST_URL] = 302 to "https://github.com/sevorp-dev/SkyPython/releases"
        assertTrue(checker().check() is CheckResult.Failed)
    }
}
