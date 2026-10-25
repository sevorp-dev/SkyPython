package dev.skyloft.skypython.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val http = FakeUpdateHttp()
    private val v6 = SemVer(0, 0, 6)
    private val apk = ByteArray(50_000) { (it * 31 % 251).toByte() }
    private val apkName = "SkyPython-0.0.6-x86_64.apk"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun publish(sumFor: ByteArray = apk) {
        http.files[Releases.apkUrl(v6, "x86_64")] = apk
        http.files[Releases.sumsUrl(v6)] = (
            "${sha(byteArrayOf(1))}  SkyPython-0.0.6-arm64-v8a.apk\n" +
                "${sha(sumFor)}  $apkName\n"
            ).toByteArray()
    }

    private fun downloader(dir: File = tmp.root) = UpdateDownloader(http, dir, Dispatchers.Unconfined)

    @Test
    fun downloadsAndVerifies() = runTest {
        publish()
        val seen = mutableListOf<Pair<Long, Long>>()
        val file = downloader().download(v6, "x86_64") { done, total -> seen += done to total }
        assertEquals(apkName, file.name)
        assertArrayEquals(apk, file.readBytes())
        assertEquals(apk.size.toLong() to apk.size.toLong(), seen.last())
        assertFalse(File(tmp.root, "$apkName.part").exists())
    }

    @Test
    fun checksumMismatchDeletesTheFile() = runTest {
        publish(sumFor = byteArrayOf(9, 9, 9))
        try {
            downloader().download(v6, "x86_64") { _, _ -> }
            fail("expected a mismatch")
        } catch (e: UpdateException) {
            assertEquals(UpdateException.Kind.Checksum, e.kind)
        }
        assertTrue(tmp.root.listFiles().orEmpty().none { it.name.startsWith(apkName) })
    }

    @Test
    fun noChecksumForTheApkIsAnError() = runTest {
        http.files[Releases.apkUrl(v6, "x86_64")] = apk
        http.files[Releases.sumsUrl(v6)] = "${sha(apk)}  SkyPython-0.0.6-universal.apk\n".toByteArray()
        try {
            downloader().download(v6, "x86_64") { _, _ -> }
            fail()
        } catch (e: UpdateException) {
            assertEquals(UpdateException.Kind.NoChecksum, e.kind)
        }
        // The APK itself was never fetched.
        assertTrue(http.requests.none { it.first.endsWith(".apk") })
    }

    @Test
    fun missingApkIsAnHttpError() = runTest {
        http.files[Releases.sumsUrl(v6)] = "${sha(apk)}  $apkName\n".toByteArray()
        try {
            downloader().download(v6, "x86_64") { _, _ -> }
            fail()
        } catch (e: UpdateException) {
            assertEquals(UpdateException.Kind.Http, e.kind)
        }
    }

    @Test
    fun offlineIsANetworkError() = runTest {
        http.failing += Releases.sumsUrl(v6)
        try {
            downloader().download(v6, "x86_64") { _, _ -> }
            fail()
        } catch (e: UpdateException) {
            assertEquals(UpdateException.Kind.Network, e.kind)
        }
    }

    @Test
    fun resumesAnInterruptedDownload() = runTest {
        publish()
        http.cutAfter[Releases.apkUrl(v6, "x86_64")] = 20_000
        try {
            downloader().download(v6, "x86_64") { _, _ -> }
            fail("the first try is cut")
        } catch (e: UpdateException) {
            assertEquals(UpdateException.Kind.Network, e.kind)
        }
        assertEquals(20_000L, File(tmp.root, "$apkName.part").length())
        val file = downloader().download(v6, "x86_64") { _, _ -> }
        assertArrayEquals(apk, file.readBytes())
        assertEquals("bytes=20000-", http.requests.last().second["Range"])
    }

    @Test
    fun serverIgnoringRangeStartsOver() = runTest {
        publish()
        File(tmp.root, "$apkName.part").writeBytes(ByteArray(100) { 7 })
        http.honourRanges = false
        val file = downloader().download(v6, "x86_64") { _, _ -> }
        assertArrayEquals(apk, file.readBytes())
    }

    @Test
    fun aVerifiedDownloadIsReused() = runTest {
        publish()
        downloader().download(v6, "x86_64") { _, _ -> }
        val apkRequests = http.requests.count { it.first.endsWith(".apk") }
        downloader().download(v6, "x86_64") { _, _ -> }
        assertEquals(apkRequests, http.requests.count { it.first.endsWith(".apk") })
    }

    @Test
    fun oldDownloadsAreRemoved() = runTest {
        publish()
        File(tmp.root, "SkyPython-0.0.5-x86_64.apk").writeText("old")
        File(tmp.root, "SkyPython-0.0.4-universal.apk.part").writeText("old")
        downloader().download(v6, "x86_64") { _, _ -> }
        assertEquals(listOf(apkName), tmp.root.list()!!.toList())
    }

    @Test
    fun clearRemovesEverything() {
        File(tmp.root, "SkyPython-0.0.5-x86_64.apk").writeText("old")
        downloader().clear()
        assertTrue(tmp.root.list().isNullOrEmpty())
    }
}
