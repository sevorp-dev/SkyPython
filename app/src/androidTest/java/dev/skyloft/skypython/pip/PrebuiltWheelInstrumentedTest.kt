package dev.skyloft.skypython.pip

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.PythonProcess
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SkyPython's wheel index end to end, against a local index (a `file:` folder) holding two
 * wheels built here from scratch: download → sha256 check → pip install → import.
 */
@RunWith(AndroidJUnit4::class)
class PrebuiltWheelInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var paths: PythonPaths
    private val indexDir = File(context.filesDir, "wheel-index-test")
    private val cacheDir = File(context.cacheDir, "wheel-index-test")

    @Before
    fun setUp(): Unit = runBlocking {
        paths = PythonInstaller(context).ensureInstalled().getOrThrow()
        indexDir.deleteRecursively()
        cacheDir.deleteRecursively()
        indexDir.mkdirs()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        val repo = PipRepository.create(context, wheels = null)
        repo.uninstall(DEMO)
        repo.uninstall(DEP)
        indexDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    private fun wheels() = WheelIndex(
        baseUrl = indexDir.toURI().toString(),
        cacheDir = cacheDir,
        abi = { paths.abi!! },
    )

    /** Writes the two wheels and an index for them; [demoSha] overrides the demo's checksum. */
    private fun publish(demoSha: String? = null, demoAbi: String = paths.abi!!) {
        val dep = wheel(DEP, mapOf("$DEP_MODULE/__init__.py" to "VALUE = 21\n"), requires = emptyList())
        val demo = wheel(
            DEMO,
            mapOf("$DEMO_MODULE/__init__.py" to "from $DEP_MODULE import VALUE as _V\nVALUE = _V * 2\n"),
            requires = listOf(DEP),
        )
        File(indexDir, dep.first).writeBytes(dep.second)
        File(indexDir, demo.first).writeBytes(demo.second)
        fun entry(file: String, bytes: ByteArray, abi: String, requires: String, sha: String = sha256Hex(bytes)) =
            """[{"version": "1.0", "abi": "$abi", "file": "$file", "sha256": "$sha", "size": ${bytes.size},
                "requires": [$requires], "summary": "test"}]"""
        File(indexDir, "index.json").writeText(
            """{"schema": 1, "python": "cp314", "packages": {
                "$DEP": ${entry(dep.first, dep.second, paths.abi!!, "")},
                "$DEMO": ${entry(demo.first, demo.second, demoAbi, "\"$DEP\"", demoSha ?: sha256Hex(demo.second))}
            }}""",
        )
    }

    @Test
    fun aPrebuiltWheelDownloadsVerifiesInstallsAndImports(): Unit = runBlocking {
        publish()
        val wheels = wheels()
        val repo = PipRepository.create(context, wheels)
        withTimeout(INSTALL_TIMEOUT_MS) {
            val log = StringBuilder()
            try {
                repo.install(DEMO).collect { log.append(it) }
            } catch (e: Exception) {
                throw AssertionError("install failed: ${e.message}\n$log", e)
            }
            // Requirement first, both verified into the wheel folder.
            assertTrue(log.toString(), log.indexOf(DEP) in 0 until log.indexOf("$DEMO 1.0"))
            assertTrue(log.toString(), "100%" in log)
            assertTrue(File(wheels.wheelDir, "${DEMO_MODULE}-1.0-py3-none-any.whl").isFile)
            assertTrue(File(wheels.wheelDir, "${DEP_MODULE}-1.0-py3-none-any.whl").isFile)

            val installed = repo.listInstalled().getOrThrow().map { PyPiJson.normalize(it.name) }
            assertTrue(installed.toString(), DEMO in installed && DEP in installed)

            val script = File(indexDir, "use_it.py").apply {
                writeText("import $DEMO_MODULE\nprint('value', $DEMO_MODULE.VALUE)\n")
            }
            val events = PythonProcess(paths).start(script).toList()
            val out = events.filterIsInstance<ConsoleEvent.Stdout>().joinToString("") { it.text }
            val err = events.filterIsInstance<ConsoleEvent.Stderr>().joinToString("") { it.text }
            assertEquals("stdout=$out stderr=$err", "value 42\n", out)
            assertEquals(ConsoleEvent.Exited(0), events.last())
        }
    }

    @Test
    fun aDamagedWheelIsRejectedBeforePip(): Unit = runBlocking {
        publish(demoSha = "0".repeat(64))
        val repo = PipRepository.create(context, wheels())
        try {
            withTimeout(INSTALL_TIMEOUT_MS) { repo.install(DEMO).toList() }
            fail("expected WheelVerificationException")
        } catch (e: WheelVerificationException) {
            assertTrue(e.file, e.file.startsWith(DEMO_MODULE))
        }
        assertTrue(repo.listInstalled().getOrThrow().none { PyPiJson.normalize(it.name) == DEMO })
    }

    @Test
    fun aWheelForAnotherAbiIsExplained(): Unit = runBlocking {
        val other = if (paths.abi == "x86_64") "arm64-v8a" else "x86_64"
        publish(demoAbi = other)
        val repo = PipRepository.create(context, wheels())
        try {
            repo.install(DEMO).toList()
            fail("expected PrebuiltUnavailableException")
        } catch (e: PrebuiltUnavailableException) {
            assertEquals(setOf(other), e.abis)
            assertEquals(paths.abi, e.abi)
        }
    }

    /** A minimal `py3-none-any` wheel of [dist] 1.0 with [files]: (file name, bytes). */
    private fun wheel(dist: String, files: Map<String, String>, requires: List<String>): Pair<String, ByteArray> {
        val module = dist.replace('-', '_')
        val info = "$module-1.0.dist-info"
        val contents = LinkedHashMap<String, ByteArray>()
        files.forEach { (path, text) -> contents[path] = text.toByteArray() }
        contents["$info/METADATA"] = buildString {
            append("Metadata-Version: 2.1\nName: $dist\nVersion: 1.0\nSummary: SkyPython wheel index test\n")
            requires.forEach { append("Requires-Dist: $it\n") }
        }.toByteArray()
        contents["$info/WHEEL"] =
            "Wheel-Version: 1.0\nGenerator: skypython-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n".toByteArray()
        contents["$info/top_level.txt"] = "$module\n".toByteArray()
        val record = StringBuilder()
        contents.forEach { (path, bytes) ->
            val hash = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            record.append("$path,sha256=$hash,${bytes.size}\n")
        }
        record.append("$info/RECORD,,\n")
        contents["$info/RECORD"] = record.toString().toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            contents.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return "$module-1.0-py3-none-any.whl" to out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEMO = "skypython-wheeltest-demo"
        const val DEP = "skypython-wheeltest-dep"
        const val DEMO_MODULE = "skypython_wheeltest_demo"
        const val DEP_MODULE = "skypython_wheeltest_dep"
        const val INSTALL_TIMEOUT_MS = 300_000L
    }
}
