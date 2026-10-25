package dev.skyloft.skypython.lang

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.runtime.LanguageServerInstaller
import dev.skyloft.skypython.runtime.LanguageServerProcess
import dev.skyloft.skypython.runtime.PythonInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real language server on the device: bundled Python + jedi/pyflakes from
 * assets/langserver. Logs latencies under the tag "LangServerTest" (`adb logcat -s LangServerTest`).
 */
@RunWith(AndroidJUnit4::class)
class LanguageServerDeviceTest {

    private suspend fun complete(source: String): List<String> {
        val lines = source.split("\n")
        val started = System.nanoTime()
        // null only while the server is (re)starting.
        val items = withTimeout(READY_TIMEOUT_MS) {
            var r: List<Completion>? = null
            while (r == null) {
                r = service.complete(source, lines.size, lines.last().length, null)
                if (r == null) delay(100)
            }
            r
        }
        Log.i(TAG, "complete ${source.replace("\n", "\\n")}: ${(System.nanoTime() - started) / 1_000_000} ms, ${items.size} items")
        return items.map { it.name }
    }

    private suspend fun diagnose(source: String): List<Diagnostic> {
        val started = System.nanoTime()
        val d = answered(service.diagnose(source, null))
        Log.i(TAG, "diagnose ${source.replace("\n", "\\n")}: ${(System.nanoTime() - started) / 1_000_000} ms -> $d")
        return d
    }

    private fun <T> answered(value: T?): T {
        assertNotNull("language server gave no answer", value as Any?)
        return value!!
    }

    @Test
    fun importJsCompletesJson(): Unit = runBlocking {
        val names = complete("import js")
        assertTrue(names.toString(), "json" in names)
    }

    @Test
    fun stringMethodCompletes(): Unit = runBlocking {
        val names = complete("\"abc\".up")
        assertTrue(names.toString(), "upper" in names)
    }

    @Test
    fun unclosedParenIsASyntaxErrorOnLine1(): Unit = runBlocking {
        val d = diagnose("x = (\n").single()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(1, d.line)
        assertTrue(d.message, d.message.startsWith("SyntaxError"))
    }

    @Test
    fun undefinedNameIsAnError(): Unit = runBlocking {
        val d = diagnose("print(undefined_thing)\n").single()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals(1, d.line)
        assertEquals(6, d.column)
        assertTrue(d.message, d.message.contains("undefined_thing"))
    }

    @Test
    fun cleanCodeHasNoProblems(): Unit = runBlocking {
        assertEquals(emptyList<Diagnostic>(), diagnose("import os\nprint(os.getcwd())\n"))
    }

    @Test
    fun bundleLivesOutsideTheUserSite(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = LanguageServerInstaller(context).ensureInstalled().getOrThrow()
        assertEquals(File(context.filesDir, "langserver/skylang_server.py"), script)
        assertTrue(File(context.filesDir, "langserver/jedi/__init__.py").isFile)
        val userSite = File(context.filesDir, "home/.local")
        assertFalse(userSite.walkTopDown().any { it.name == "jedi" || it.name == "pyflakes" })
    }

    companion object {
        private const val TAG = "LangServerTest"
        /** First start unpacks Python and the bundle and compiles bytecode: slow emulators need time. */
        private const val READY_TIMEOUT_MS = 180_000L

        private lateinit var scope: CoroutineScope
        private lateinit var service: LanguageService

        @BeforeClass @JvmStatic
        fun startServer() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            service = LanguageService(
                scope = scope,
                start = {
                    val paths = PythonInstaller(context).ensureInstalled().getOrThrow()
                    val script = LanguageServerInstaller(context).ensureInstalled().getOrThrow()
                    LanguageServerProcess.start(paths, script)
                },
                config = LanguageService.Config(readyTimeoutMs = READY_TIMEOUT_MS),
            )
            val started = System.nanoTime()
            runBlocking { withTimeout(READY_TIMEOUT_MS) { service.diagnose("", null) } }
            Log.i(TAG, "server start to first answer: ${(System.nanoTime() - started) / 1_000_000} ms")
        }

        @AfterClass @JvmStatic
        fun stopServer() {
            service.shutdown()
            scope.cancel()
        }
    }
}
