package dev.skyloft.skypython.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ProjectZipTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun project(): File {
        val root = tmp.newFolder("my game")
        File(root, "main.py").writeText("print('hi')\n")
        File(root, "lib").mkdirs()
        File(root, "lib/util.py").writeText("X = 1\n")
        File(root, "assets").mkdirs() // empty folder
        File(root, "__pycache__").mkdirs()
        File(root, "__pycache__/main.cpython-314.pyc").writeText("junk")
        File(root, "lib/__pycache__").mkdirs()
        File(root, "lib/__pycache__/util.cpython-314.pyc").writeText("junk")
        return root
    }

    @Test
    fun zipIsNamedAfterTheFolderAndKeepsItAsTopLevelDirectory() {
        val zip = zipFolder(project(), tmp.newFolder("out"))
        assertEquals("my game.zip", zip.name)
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.sorted()
            assertEquals(
                listOf("my game/", "my game/assets/", "my game/lib/", "my game/lib/util.py", "my game/main.py"),
                names,
            )
            assertEquals("print('hi')\n", z.getInputStream(z.getEntry("my game/main.py")).reader().readText())
        }
    }

    @Test
    fun bytecodeCachesAreLeftOut() {
        val zip = zipFolder(project(), tmp.newFolder("out"))
        ZipFile(zip).use { z -> assertTrue(z.entries().toList().none { "__pycache__" in it.name || it.name.endsWith(".pyc") }) }
    }

    @Test
    fun zippingAgainReplacesTheOldArchive() {
        val src = project()
        val out = tmp.newFolder("out")
        zipFolder(src, out)
        File(src, "new.py").writeText("pass\n")
        val zip = zipFolder(src, out)
        ZipFile(zip).use { z -> assertTrue(z.getEntry("my game/new.py") != null) }
    }

    @Test
    fun mimeTypesFollowTheExtension() {
        assertEquals("text/x-python", mimeTypeFor(File("a.py")))
        assertEquals("application/zip", mimeTypeFor(File("a.zip")))
        assertEquals("text/plain", mimeTypeFor(File("notes.txt")))
        assertEquals("application/json", mimeTypeFor(File("data.JSON")))
        assertEquals("application/octet-stream", mimeTypeFor(File("blob.bin")))
    }
}
