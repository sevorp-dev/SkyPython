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
    fun definitionInAnotherFileOfTheProject(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "navproj").apply { mkdirs() }
        File(dir, "helper.py").writeText("LOUD = True\n\ndef shout(text):\n    return text.upper()\n")
        val main = File(dir, "main.py")
        val source = "from helper import shout\nshout('hi')\n"
        main.writeText(source)
        val defs = answered(service.definition(source, 2, 2, main.canonicalPath))
        Log.i(TAG, "definition across files -> $defs")
        val d = defs.single()
        assertEquals(File(dir, "helper.py").canonicalPath, File(d.path!!).canonicalPath)
        assertEquals(Origin.PROJECT, d.origin)
        assertEquals(3, d.line)
        assertEquals(4, d.column)
        dir.deleteRecursively()
    }

    @Test
    fun builtinDefinitionIsInTheStdlibStubs(): Unit = runBlocking {
        val d = answered(service.definition("print('x')\n", 1, 2, null)).also { Log.i(TAG, "print -> $it") }.first()
        assertEquals(Origin.STDLIB, d.origin)
        assertTrue(d.path, d.path!!.endsWith("builtins.pyi"))
        val json = answered(service.definition("import json\njson.dumps\n", 2, 6, null)).first()
        Log.i(TAG, "json.dumps -> $json")
        assertEquals(Origin.STDLIB, json.origin)
    }

    @Test
    fun outlineAndSignature(): Unit = runBlocking {
        val items = answered(service.outline("class A:\n    def m(self):\n        pass\n"))
        assertEquals("A", items.single().name)
        assertEquals("m", items.single().children.single().name)
        var sig: Signature? = null
        withTimeout(READY_TIMEOUT_MS) { while (sig == null) { sig = service.signature("print(", 1, 6, null); if (sig == null) delay(200) } }
        assertEquals("*values", sig!!.short.first())
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
