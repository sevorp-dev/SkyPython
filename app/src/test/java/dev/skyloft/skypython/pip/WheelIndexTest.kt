package dev.skyloft.skypython.pip

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest

class WheelIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- Parsing ---------------------------------------------------------------------------

    private fun entry(
        name: String,
        version: String,
        abi: String = "arm64-v8a",
        requires: String = "",
        sha: String = "a".repeat(64),
        file: String = "$name-$version-cp314-cp314-android_24_${abi.replace('-', '_')}.whl",
        size: Long = 10,
    ) = """{"version": "$version", "abi": "$abi", "file": "$file", "sha256": "$sha", "size": $size,
        "requires": [$requires], "summary": "About $name"}"""

    private fun index(vararg packages: Pair<String, List<String>>, schema: Int = 1, python: String = "cp314") =
        """{"schema": $schema, "python": "$python", "packages": {""" +
            packages.joinToString(",") { (name, entries) -> "\"$name\": [${entries.joinToString(",")}]" } +
            "}}"

    @Test
    fun parsesTheContract() {
        val data = WheelIndexJson.parse(
            index("numpy" to listOf(entry("numpy", "2.5.4"), entry("numpy", "2.5.4", abi = "x86_64"))),
        )!!
        assertEquals("cp314", data.python)
        val wheels = data.entries("NumPy")
        assertEquals(2, wheels.size)
        val w = wheels[0]
        assertEquals("numpy", w.name)
        assertEquals("2.5.4", w.version)
        assertEquals("arm64-v8a", w.abi)
        assertEquals("numpy-2.5.4-cp314-cp314-android_24_arm64_v8a.whl", w.file)
        assertEquals(10L, w.size)
        assertEquals(emptyList<String>(), w.requires)
        assertEquals("About numpy", w.summary)
    }

    @Test
    fun namesAreNormalized() {
        val data = WheelIndexJson.parse(index("Py_Thing" to listOf(entry("Py_Thing", "1.0"))))!!
        assertEquals(1, data.entries("py-thing").size)
        assertEquals(1, data.entries("PY.THING").size)
    }

    @Test
    fun rejectsOtherSchemasPythonsAndGarbage() {
        assertNull(WheelIndexJson.parse(index("numpy" to listOf(entry("numpy", "1")), schema = 2)))
        assertNull(WheelIndexJson.parse(index("numpy" to listOf(entry("numpy", "1")), python = "cp313")))
        assertNull(WheelIndexJson.parse("""{"python": "cp314", "packages": {}}"""))
        assertNull(WheelIndexJson.parse("not json"))
        assertNull(WheelIndexJson.parse("[]"))
        assertNull(WheelIndexJson.parse("""{"schema": 1, "python": "cp314"}"""))
    }

    @Test
    fun skipsUnsafeOrIncompleteEntries() {
        val data = WheelIndexJson.parse(
            index(
                "numpy" to listOf(
                    entry("numpy", "1.0", file = "../evil.whl"),
                    entry("numpy", "1.1", file = "sub/dir.whl"),
                    entry("numpy", "1.2", file = "numpy.tar.gz"),
                    entry("numpy", "1.3", sha = "xyz"),
                    entry("numpy", "1.4", size = -1),
                    """{"version": "1.5"}""",
                    entry("numpy", "2.0"),
                ),
            ),
        )!!
        assertEquals(listOf("2.0"), data.entries("numpy").map { it.version })
    }

    // --- Versions --------------------------------------------------------------------------

    @Test
    fun versionsCompareNumerically() {
        assertTrue(Versions.compare("2.10.0", "2.9.9") > 0)
        assertTrue(Versions.compare("2.5", "2.5.0") == 0)
        assertTrue(Versions.compare("1.0", "1.0.1") < 0)
        assertTrue(Versions.compare("2.0.0rc1", "2.0.0") < 0)
    }

    @Test
    fun versionSpecsMatch() {
        assertTrue(Versions.matches("2.5.4", ""))
        assertTrue(Versions.matches("2.5.4", "==2.5.4"))
        assertFalse(Versions.matches("2.5.4", "==2.5.3"))
        assertTrue(Versions.matches("2.5.4", "==2.5.*"))
        assertFalse(Versions.matches("2.6.0", "==2.5.*"))
        assertTrue(Versions.matches("2.5.4", ">=2,<3"))
        assertFalse(Versions.matches("3.0", ">=2,<3"))
        assertTrue(Versions.matches("2.5.4", "!=2.5.3"))
        assertFalse(Versions.matches("2.5.4", "!=2.5.4"))
        assertTrue(Versions.matches("2.5.4", "~=2.5"))
        assertFalse(Versions.matches("3.0", "~=2.5"))
        assertTrue(Versions.matches("2.5.4", ">2.5"))
        assertTrue(Versions.matches("2.5.4", "<=2.5.4"))
        assertTrue(Versions.matches("2.5.4", "===2.5.4"))
    }

    // --- Planning --------------------------------------------------------------------------

    private val bigIndex = WheelIndexJson.parse(
        index(
            "numpy" to listOf(
                entry("numpy", "2.4.0"), entry("numpy", "2.5.4"), entry("numpy", "2.5.4", abi = "x86_64"),
            ),
            "pandas" to listOf(entry("pandas", "3.0.0", requires = "\"numpy\", \"tzdata\"")),
            "scipy" to listOf(entry("scipy", "1.16.0", requires = "\"numpy\"")),
            "sklearn-thing" to listOf(entry("sklearn-thing", "1.0", requires = "\"scipy\", \"pandas\", \"numpy\"")),
            "loop-a" to listOf(entry("loop-a", "1", requires = "\"loop-b\"")),
            "loop-b" to listOf(entry("loop-b", "1", requires = "\"loop-a\"")),
            "x86-only" to listOf(entry("x86-only", "1", abi = "x86_64")),
            "uses-x86-only" to listOf(entry("uses-x86-only", "1", requires = "\"x86-only\"")),
        ),
    )!!

    private fun planned(spec: String, abi: String = "arm64-v8a"): List<String> =
        (bigIndex.plan(spec, abi) as WheelPlan.Ready).wheels.map { "${it.name}==${it.version}" }

    @Test
    fun picksTheNewestWheelForTheAbi() {
        assertEquals(listOf("numpy==2.5.4"), planned("numpy"))
        val w = (bigIndex.plan("numpy", "x86_64") as WheelPlan.Ready).wheels.single()
        assertEquals("x86_64", w.abi)
    }

    @Test
    fun honorsTheVersionSpec() {
        assertEquals(listOf("numpy==2.4.0"), planned("numpy==2.4.0"))
        assertEquals(listOf("numpy==2.4.0"), planned("numpy<2.5"))
        assertEquals(listOf("numpy==2.5.4"), planned("numpy[extra]>=2"))
        val none = bigIndex.plan("numpy==1.26.0", "arm64-v8a") as WheelPlan.NoVersion
        assertEquals("numpy", none.name)
        assertEquals("==1.26.0", none.spec)
        assertEquals(listOf("2.5.4", "2.4.0"), none.versions)
    }

    @Test
    fun requirementsComeFirstOnceEach() {
        // tzdata isn't in the index: pip gets it from PyPI.
        assertEquals(listOf("numpy==2.5.4", "pandas==3.0.0"), planned("pandas"))
        assertEquals(
            listOf("numpy==2.5.4", "scipy==1.16.0", "pandas==3.0.0", "sklearn-thing==1.0"),
            planned("sklearn-thing"),
        )
    }

    @Test
    fun requirementCyclesTerminate() {
        assertEquals(listOf("loop-b==1", "loop-a==1"), planned("loop-a"))
    }

    @Test
    fun otherAbisAreReported() {
        val plan = bigIndex.plan("x86-only", "armeabi-v7a") as WheelPlan.Unavailable
        assertEquals("x86-only", plan.name)
        assertEquals("armeabi-v7a", plan.abi)
        assertEquals(setOf("x86_64"), plan.abis)
        // A requirement missing for the ABI makes the whole plan unavailable.
        val dep = bigIndex.plan("uses-x86-only", "arm64-v8a") as WheelPlan.Unavailable
        assertEquals("x86-only", dep.name)
    }

    @Test
    fun unknownNamesAreNotInTheIndex() {
        assertEquals(WheelPlan.NotInIndex, bigIndex.plan("requests", "arm64-v8a"))
        assertEquals(WheelPlan.NotInIndex, bigIndex.plan("-r x", "arm64-v8a"))
    }

    @Test
    fun statusForTheUi() {
        val catalog = WheelCatalog(bigIndex, "x86_64")
        assertEquals("2.5.4", (catalog.status("NumPy") as PrebuiltStatus.Available).wheel.version)
        assertEquals(setOf("arm64-v8a"), (catalog.status("scipy") as PrebuiltStatus.OtherAbis).abis)
        assertNull(catalog.status("requests"))
    }

    // --- Fetching and caching --------------------------------------------------------------

    private class FakeHttp : WheelHttp {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var reply: (String, Map<String, String>) -> WheelResponse = { _, _ -> WheelResponse(404) }
        override fun get(url: String, headers: Map<String, String>): WheelResponse {
            requests += url to headers
            return reply(url, headers)
        }
    }

    private val http = FakeHttp()
    private var now = 1_000_000L

    private fun wheelIndex(io: kotlinx.coroutines.CoroutineDispatcher) = WheelIndex(
        baseUrl = "https://example.test/wheels",
        cacheDir = tmp.root,
        abi = { "arm64-v8a" },
        http = http,
        clock = { now },
        io = io,
    )

    private val good = index("numpy" to listOf(entry("numpy", "2.5.4")))

    private fun ok(body: String, vararg headers: Pair<String, String>) =
        WheelResponse(200, headers.toMap(), ByteArrayInputStream(body.toByteArray()))

    @Test
    fun fetchesTheIndexAndCachesItForAnHour() = runTest {
        http.reply = { _, _ -> ok(good, "etag" to "\"v1\"", "last-modified" to "Sat, 24 Oct 2026 10:00:00 GMT") }
        val idx = wheelIndex(StandardTestDispatcher(testScheduler))
        assertEquals("2.5.4", idx.load()!!.entries("numpy").single().version)
        assertEquals("https://example.test/wheels/index.json", http.requests.single().first)
        // Fresh: no request, even from a new instance (the disk cache).
        now += 30 * 60_000
        assertNotNull(wheelIndex(StandardTestDispatcher(testScheduler)).load())
        assertEquals(1, http.requests.size)
        // Stale: a conditional request; 304 keeps the cached copy.
        now += 31 * 60_000
        http.reply = { _, _ -> WheelResponse(304) }
        assertEquals("2.5.4", wheelIndex(StandardTestDispatcher(testScheduler)).load()!!.entries("numpy").single().version)
        val headers = http.requests.last().second
        assertEquals("\"v1\"", headers["If-None-Match"])
        assertEquals("Sat, 24 Oct 2026 10:00:00 GMT", headers["If-Modified-Since"])
        // The 304 renewed the cache.
        now += 10 * 60_000
        wheelIndex(StandardTestDispatcher(testScheduler)).load()
        assertEquals(2, http.requests.size)
    }

    @Test
    fun refreshReadsTheWholeIndexAgainEvenWhenTheCacheIsFresh() = runTest {
        http.reply = { _, _ -> ok(good, "etag" to "\"v1\"") }
        val idx = wheelIndex(StandardTestDispatcher(testScheduler))
        idx.load()
        http.reply = { _, _ -> ok(index("numpy" to listOf(entry("numpy", "2.5.5")))) }
        assertEquals("2.5.5", idx.refresh()!!.index.entries("numpy").single().version)
        assertEquals(2, http.requests.size)
        assertEquals(null, http.requests.last().second["If-None-Match"])
        // The fresh copy is what later reads see.
        assertEquals("2.5.5", idx.load()!!.entries("numpy").single().version)
    }

    @Test
    fun offlineFallsBackToTheCachedCopy() = runTest {
        http.reply = { _, _ -> ok(good) }
        wheelIndex(StandardTestDispatcher(testScheduler)).load()
        now += 2 * 3_600_000
        http.reply = { _, _ -> throw UnknownHostException("raw.githubusercontent.com") }
        assertEquals("2.5.4", wheelIndex(StandardTestDispatcher(testScheduler)).load()!!.entries("numpy").single().version)
    }

    @Test
    fun offlineWithoutACacheHasNoIndex() = runTest {
        http.reply = { _, _ -> throw UnknownHostException("raw.githubusercontent.com") }
        assertNull(wheelIndex(StandardTestDispatcher(testScheduler)).load())
        http.reply = { _, _ -> WheelResponse(404) }
        assertNull(wheelIndex(StandardTestDispatcher(testScheduler)).load())
    }

    @Test
    fun aBrokenIndexDoesNotReplaceTheCachedOne() = runTest {
        http.reply = { _, _ -> ok(good) }
        wheelIndex(StandardTestDispatcher(testScheduler)).load()
        now += 2 * 3_600_000
        http.reply = { _, _ -> ok("""{"schema": 99}""") }
        assertEquals("2.5.4", wheelIndex(StandardTestDispatcher(testScheduler)).load()!!.entries("numpy").single().version)
    }

    @Test
    fun catalogUsesTheRuntimeAbi() = runTest {
        http.reply = { _, _ -> ok(good) }
        val catalog = wheelIndex(StandardTestDispatcher(testScheduler)).catalog()!!
        assertEquals("arm64-v8a", catalog.abi)
        assertTrue(catalog.status("numpy") is PrebuiltStatus.Available)
    }

    // --- Downloading -----------------------------------------------------------------------

    private val payload = ByteArray(100_000) { (it * 31 % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun wheel(bytes: ByteArray = payload, sha256: String = sha(payload)) = IndexWheel(
        name = "numpy", version = "2.5.4", abi = "arm64-v8a",
        file = "numpy-2.5.4-cp314-cp314-android_24_arm64_v8a.whl",
        sha256 = sha256, size = bytes.size.toLong(), requires = emptyList(), summary = null,
    )

    @Test
    fun downloadsAndVerifiesIntoTheWheelsFolder() = runTest {
        http.reply = { _, _ -> WheelResponse(200, emptyMap(), ByteArrayInputStream(payload)) }
        val idx = wheelIndex(StandardTestDispatcher(testScheduler))
        val progress = mutableListOf<Long>()
        val file = idx.download(wheel()) { done, total -> assertEquals(payload.size.toLong(), total); progress += done }
        assertEquals(File(File(tmp.root, "wheels"), wheel().file), file)
        assertArrayEquals(payload, file.readBytes())
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue(progress.size > 1)
        assertEquals("https://example.test/wheels/${wheel().file}", http.requests.single().first)
        assertFalse(File(file.path + ".part").exists())
        // Already there and intact: no second download.
        idx.download(wheel()) { _, _ -> }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun aChecksumMismatchIsRejected() = runTest {
        val tampered = payload.copyOf().also { it[5] = (it[5] + 1).toByte() }
        http.reply = { _, _ -> WheelResponse(200, emptyMap(), ByteArrayInputStream(tampered)) }
        val idx = wheelIndex(StandardTestDispatcher(testScheduler))
        try {
            idx.download(wheel()) { _, _ -> }
            fail("expected WheelVerificationException")
        } catch (e: WheelVerificationException) {
            assertEquals(wheel().file, e.file)
        }
        val dir = File(tmp.root, "wheels")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aTamperedCachedWheelIsDownloadedAgain() = runTest {
        val dir = File(tmp.root, "wheels").apply { mkdirs() }
        File(dir, wheel().file).writeBytes(payload.copyOf().also { it[0] = 9 })
        http.reply = { _, _ -> WheelResponse(200, emptyMap(), ByteArrayInputStream(payload)) }
        val file = wheelIndex(StandardTestDispatcher(testScheduler)).download(wheel()) { _, _ -> }
        assertArrayEquals(payload, file.readBytes())
        assertEquals(1, http.requests.size)
    }

    @Test
    fun anInterruptedDownloadResumes() = runTest {
        val dir = File(tmp.root, "wheels").apply { mkdirs() }
        File(dir, wheel().file + ".part").writeBytes(payload.copyOfRange(0, 40_000))
        http.reply = { _, headers ->
            assertEquals("bytes=40000-", headers["Range"])
            WheelResponse(206, emptyMap(), ByteArrayInputStream(payload.copyOfRange(40_000, payload.size)))
        }
        val progress = mutableListOf<Long>()
        val file = wheelIndex(StandardTestDispatcher(testScheduler)).download(wheel()) { done, _ -> progress += done }
        assertArrayEquals(payload, file.readBytes())
        assertEquals(40_000L, progress.first())
    }

    @Test
    fun aServerIgnoringTheRangeStartsOver() = runTest {
        val dir = File(tmp.root, "wheels").apply { mkdirs() }
        File(dir, wheel().file + ".part").writeBytes(payload.copyOfRange(0, 40_000))
        http.reply = { _, _ -> WheelResponse(200, emptyMap(), ByteArrayInputStream(payload)) }
        val file = wheelIndex(StandardTestDispatcher(testScheduler)).download(wheel()) { _, _ -> }
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun aFailedDownloadKeepsThePartForLater() = runTest {
        http.reply = { _, _ ->
            WheelResponse(200, emptyMap(), object : java.io.InputStream() {
                var n = 0
                override fun read(): Int = if (n < 30_000) payload[n++].toInt() and 0xff else throw IOException("connection reset")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n >= 30_000) throw IOException("connection reset")
                    val k = minOf(len, 30_000 - n)
                    System.arraycopy(payload, n, b, off, k); n += k; return k
                }
            })
        }
        try {
            wheelIndex(StandardTestDispatcher(testScheduler)).download(wheel()) { _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(e is WheelVerificationException)
        }
        assertEquals(30_000L, File(File(tmp.root, "wheels"), wheel().file + ".part").length())
    }

    @Test
    fun httpErrorsFail() = runTest {
        http.reply = { _, _ -> WheelResponse(500) }
        try {
            wheelIndex(StandardTestDispatcher(testScheduler)).download(wheel()) { _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!, "500" in e.message!!)
        }
    }
}
