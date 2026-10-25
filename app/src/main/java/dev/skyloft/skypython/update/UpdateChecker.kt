package dev.skyloft.skypython.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface CheckResult {
    /** [notes]: the release notes' Markdown, null when they couldn't be read. */
    data class Available(val version: SemVer, val notes: String?) : CheckResult
    data class UpToDate(val latest: SemVer) : CheckResult
    data class Failed(val reason: String) : CheckResult
}

/** Asks GitHub which release is the latest and compares it with [currentVersion]. */
class UpdateChecker(
    private val http: UpdateHttp,
    private val currentVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun check(): CheckResult = withContext(io) {
        val head = try {
            http.head(Releases.LATEST_URL)
        } catch (e: IOException) {
            return@withContext CheckResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        val tag = Releases.tagFromLocation(head.location)
            ?: return@withContext CheckResult.Failed("HTTP ${head.code}, no release tag")
        val latest = SemVer.parse(tag) ?: return@withContext CheckResult.Failed("Bad tag $tag")
        if (!Releases.isNewer(latest.toString(), currentVersion)) return@withContext CheckResult.UpToDate(latest)
        CheckResult.Available(latest, notes(latest))
    }

    private fun notes(version: SemVer): String? = try {
        http.get(Releases.notesUrl(version)).use { reply ->
            if (reply.code == 200) reply.body?.readBytes()?.toString(Charsets.UTF_8) else null
        }
    } catch (e: IOException) {
        null
    }
}
