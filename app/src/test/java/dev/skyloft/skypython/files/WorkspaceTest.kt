package dev.skyloft.skypython.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var ws: Workspace

    @Before
    fun setUp() {
        root = File(tmp.root, "projects")
        ws = Workspace(root)
    }

    @Test
    fun listSortsDirsFirstThenNameCaseInsensitive() {
        root.mkdirs()
        File(root, "b.py").writeText("")
        File(root, "A.py").writeText("")
        File(root, "zdir").mkdir()
        File(root, "Cdir").mkdir()
        assertEquals(listOf("Cdir", "zdir", "A.py", "b.py"), ws.list().map { it.name })
    }

    @Test
    fun listOfMissingRootIsEmpty() {
        assertEquals(emptyList<File>(), ws.list())
    }

    @Test
    fun createFileWithSpacesSucceeds() {
        val f = ws.createFile(root, "a b.py").getOrThrow()
        assertTrue(f.isFile)
        assertEquals(File(root, "a b.py").canonicalFile, f.canonicalFile)
    }

    @Test
    fun createFileRejectsInvalidNames() {
        root.mkdirs()
        for (bad in listOf("a/b", "", "   ", "..", ".", "a\\b", "a\u0000b", "x".repeat(256))) {
            assertTrue("expected failure for '$bad'", ws.createFile(root, bad).isFailure)
        }
        assertEquals(emptyList<File>(), ws.list())
    }

    @Test
    fun isValidNameRules() {
        assertTrue(isValidName("a b.py"))
        assertTrue(isValidName(".hidden"))
        assertTrue(isValidName("x".repeat(255)))
        assertFalse(isValidName("x".repeat(256)))
        // 128 two-byte chars = 256 bytes
        assertFalse(isValidName("я".repeat(128)))
        assertFalse(isValidName("a/b"))
        assertFalse(isValidName(""))
        assertFalse(isValidName(" "))
        assertFalse(isValidName("."))
        assertFalse(isValidName(".."))
    }

    @Test
    fun createExistingNameFails() {
        ws.createFile(root, "x.py").getOrThrow()
        assertTrue(ws.createFile(root, "x.py").isFailure)
        assertTrue(ws.createDir(root, "x.py").isFailure)
        ws.createDir(root, "d").getOrThrow()
        assertTrue(ws.createDir(root, "d").isFailure)
        assertTrue(ws.createFile(root, "d").isFailure)
    }

    @Test
    fun createDirInNestedDir() {
        val d = ws.createDir(root, "test dir").getOrThrow()
        assertTrue(d.isDirectory)
        val f = ws.createFile(d, "a b.py").getOrThrow()
        assertTrue(f.isFile)
        assertEquals(listOf("a b.py"), ws.list(d).map { it.name })
    }

    @Test
    fun renameWorksAndRejectsExistingTarget() {
        val a = ws.createFile(root, "a.py").getOrThrow()
        a.writeText("A")
        val b = ws.createFile(root, "b.py").getOrThrow()
        b.writeText("B")
        assertTrue(ws.rename(a, "b.py").isFailure)
        assertEquals("B", b.readText())
        assertEquals("A", a.readText())

        val c = ws.rename(a, "c d.py").getOrThrow()
        assertEquals("c d.py", c.name)
        assertEquals("A", c.readText())
        assertFalse(a.exists())
    }

    @Test
    fun renameRejectsInvalidName() {
        val a = ws.createFile(root, "a.py").getOrThrow()
        assertTrue(ws.rename(a, "x/y.py").isFailure)
        assertTrue(ws.rename(a, "..").isFailure)
        assertTrue(a.exists())
    }

    @Test
    fun renameRootFails() {
        root.mkdirs()
        assertTrue(ws.rename(root, "other").isFailure)
        assertTrue(root.isDirectory)
    }

    @Test
    fun deleteRemovesNonEmptyDir() {
        val d = ws.createDir(root, "d").getOrThrow()
        val sub = ws.createDir(d, "sub").getOrThrow()
        ws.createFile(sub, "x.py").getOrThrow()
        ws.createFile(d, "y.py").getOrThrow()
        ws.delete(d).getOrThrow()
        assertFalse(d.exists())
        assertTrue(root.isDirectory)
    }

    @Test
    fun deleteRootFails() {
        ws.createFile(root, "x.py").getOrThrow()
        assertTrue(ws.delete(root).isFailure)
        assertTrue(File(root, "x.py").exists())
    }

    @Test
    fun ensureHelloCreatesOnceAndKeepsEdits() {
        val hello = ws.ensureHello()
        assertEquals("hello.py", hello.name)
        assertEquals(HELLO_PY, hello.readText())
        hello.writeText("print('mine')\n")
        val again = ws.ensureHello()
        assertEquals(hello.canonicalFile, again.canonicalFile)
        assertEquals("print('mine')\n", again.readText())
    }

    @Test
    fun helloContentIsExact() {
        assertEquals(
            "# Welcome to SkyPython!\n" +
                "name = input(\"What's your name? \")\n" +
                "print(f\"Hello, {name}! Python is running on your phone.\")\n",
            HELLO_PY,
        )
    }

    @Test
    fun pathsOutsideRootAreRejected() {
        root.mkdirs()
        val outside = tmp.newFolder("outside")
        val victim = File(outside, "victim.py").apply { writeText("keep") }

        assertTrue(ws.createFile(outside, "x.py").isFailure)
        assertFalse(File(outside, "x.py").exists())
        assertTrue(ws.createDir(outside, "d").isFailure)
        assertTrue(ws.rename(victim, "renamed.py").isFailure)
        assertTrue(victim.exists())
        assertTrue(ws.delete(victim).isFailure)
        assertTrue(victim.exists())
        assertTrue(ws.delete(outside).isFailure)
        assertTrue(outside.exists())
        assertTrue(runCatching { ws.list(outside) }.let { it.isFailure || it.getOrThrow().isEmpty() })

        // traversal via relative path segments
        val sneaky = File(root, "../outside")
        assertTrue(ws.createFile(sneaky, "y.py").isFailure)
        assertFalse(File(outside, "y.py").exists())
        assertTrue(ws.delete(File(root, "../outside/victim.py")).isFailure)
        assertTrue(victim.exists())
    }

    @Test
    fun siblingDirWithRootPrefixIsOutside() {
        root.mkdirs()
        val sibling = File(tmp.root, "projects2").apply { mkdirs() }
        assertTrue(ws.createFile(sibling, "x.py").isFailure)
        assertFalse(File(sibling, "x.py").exists())
    }

    @Test
    fun containsChecksRoot() {
        root.mkdirs()
        assertTrue(ws.contains(root))
        assertTrue(ws.contains(File(root, "a/b.py")))
        assertFalse(ws.contains(tmp.root))
        assertFalse(ws.contains(File(tmp.root, "projects2")))
    }
}
