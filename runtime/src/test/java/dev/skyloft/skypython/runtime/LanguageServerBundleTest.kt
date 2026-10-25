package dev.skyloft.skypython.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LanguageServerBundleTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zos ->
            for ((name, text) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val bundleV1 = zip(
        "skylang_server.py" to "print('v1')",
        "jedi/__init__.py" to "",
        "parso/python/grammar314.txt" to "grammar",
    )

    private class CountingOpener(private val bytes: ByteArray) : () -> InputStream {
        var opens = 0
        override fun invoke(): InputStream { opens++; return ByteArrayInputStream(bytes) }
    }

    @Test
    fun unpacksOnFirstUseAndReturnsTheScript() {
        val target = File(tmp.root, "langserver")
        val script = LanguageServerBundle.ensureInstalled(target, "v1", CountingOpener(bundleV1))
        assertEquals(File(target, "skylang_server.py"), script)
        assertEquals("print('v1')", script.readText())
        assertEquals("grammar", File(target, "parso/python/grammar314.txt").readText())
        assertEquals("v1", File(target, LanguageServerBundle.MARKER).readText())
    }

    @Test
    fun sameVersionIsNotUnpackedAgain() {
        val target = File(tmp.root, "langserver")
        LanguageServerBundle.ensureInstalled(target, "v1", CountingOpener(bundleV1))
        val opener = CountingOpener(bundleV1)
        LanguageServerBundle.ensureInstalled(target, "v1", opener)
        assertEquals(0, opener.opens)
    }

    @Test
    fun newVersionReplacesTheOldInstallCompletely() {
        val target = File(tmp.root, "langserver")
        LanguageServerBundle.ensureInstalled(target, "v1", CountingOpener(bundleV1))
        File(target, "jedi/__pycache__").mkdirs() // runtime leftovers go with the old version
        val v2 = zip("skylang_server.py" to "print('v2')", "pyflakes/__init__.py" to "")
        LanguageServerBundle.ensureInstalled(target, "v2", CountingOpener(v2))
        assertEquals("print('v2')", File(target, "skylang_server.py").readText())
        assertFalse(File(target, "jedi").exists())
        assertTrue(File(target, "pyflakes/__init__.py").isFile)
        assertEquals("v2", File(target, LanguageServerBundle.MARKER).readText())
        // No staging or backup directories left next to it.
        assertEquals(listOf("langserver"), tmp.root.list()!!.toList())
    }

    @Test
    fun deletedScriptIsRestoredEvenWithAMatchingMarker() {
        val target = File(tmp.root, "langserver")
        LanguageServerBundle.ensureInstalled(target, "v1", CountingOpener(bundleV1))
        File(target, "skylang_server.py").delete()
        val script = LanguageServerBundle.ensureInstalled(target, "v1", CountingOpener(bundleV1))
        assertTrue(script.isFile)
    }

    @Test
    fun interruptedUnpackKeepsThePreviousInstall() {
        val target = File(tmp.root, "langserver")
        LanguageServerBundle.ensureInstalled(target, "v1", CountingOpener(bundleV1))
        val truncated = zip("skylang_server.py" to "x".repeat(10_000)).copyOf(60)
        try {
            LanguageServerBundle.ensureInstalled(target, "v2", { ByteArrayInputStream(truncated) })
            fail("a truncated zip must not install")
        } catch (e: Exception) {
            // expected
        }
        assertEquals("v1", File(target, LanguageServerBundle.MARKER).readText())
        assertEquals("print('v1')", File(target, "skylang_server.py").readText())
    }

    @Test
    fun bundleWithoutTheScriptFails() {
        try {
            LanguageServerBundle.ensureInstalled(File(tmp.root, "ls"), "v1", { ByteArrayInputStream(zip("a.py" to "")) })
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("skylang_server.py"))
        }
    }
}
