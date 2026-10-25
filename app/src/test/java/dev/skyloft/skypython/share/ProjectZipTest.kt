package dev.skyloft.skypython.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
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

    private fun entries(zip: File) = ZipFile(zip).use { z -> z.entries().toList().map { it.name }.sorted() }

    private fun symlink(link: File, target: File) {
        val made = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue("symlinks not permitted on this host", made)
    }

    @Test
    fun zipIsNamedAfterTheFolderAndKeepsItAsTopLevelDirectory() {
        val zip = zipFolder(project(), tmp.newFolder("out"))
        assertEquals("my game.zip", zip.name)
        ZipFile(zip).use { z ->
            assertEquals(
                listOf("my game/", "my game/assets/", "my game/lib/", "my game/lib/util.py", "my game/main.py"),
                z.entries().toList().map { it.name }.sorted(),
            )
            assertEquals("print('hi')\n", z.getInputStream(z.getEntry("my game/main.py")).reader().readText())
        }
    }

    @Test
    fun bytecodeCachesAreLeftOut() {
        val zip = zipFolder(project(), tmp.newFolder("out"))
        assertTrue(entries(zip).none { "__pycache__" in it || it.endsWith(".pyc") })
    }

    @Test
    fun zippingAgainGivesAFreshArchiveWithoutTouchingTheOneBeingShared() {
        val src = project()
        val out = tmp.newFolder("out")
        val first = zipFolder(src, out)
        File(src, "new.py").writeText("pass\n")
        val second = zipFolder(src, out)
        assertEquals("my game.zip", second.name)
        assertNotEquals(first, second)
        assertTrue("the earlier archive may still be read by the receiving app", first.isFile)
        assertFalse("my game/new.py" in entries(first))
        assertTrue("my game/new.py" in entries(second))
    }

    @Test
    fun onlyTheLastFewArchivesAreKept() {
        val src = project()
        val out = tmp.newFolder("out")
        val zips = (1..ZIPS_KEPT + 3).map { zipFolder(src, out) }
        assertTrue(zips.takeLast(ZIPS_KEPT).all { it.isFile })
        assertTrue(zips.dropLast(ZIPS_KEPT).none { it.exists() })
        assertEquals(ZIPS_KEPT, out.listFiles()!!.size)
    }

    @Test
    fun aLinkBackUpTheTreeIsSkippedNotFollowedForever() {
        val src = project()
        symlink(File(src, "lib/loop"), src) // ln -s .. loop
        val zip = zipFolder(src, tmp.newFolder("out"))
        assertTrue(entries(zip).none { "loop" in it })
        assertTrue("my game/lib/util.py" in entries(zip))
    }

    @Test
    fun linksToFilesOutsideTheProjectAreNotPacked() {
        val src = project()
        val secret = tmp.newFolder("app-private")
        File(secret, "token.txt").writeText("secret")
        symlink(File(src, "data"), secret) // ln -s ~/.. data
        symlink(File(src, "token.txt"), File(secret, "token.txt"))
        val names = entries(zipFolder(src, tmp.newFolder("out")))
        assertTrue(names.toString(), names.none { "data" in it || "token" in it })
        assertTrue("my game/main.py" in names)
    }

    @Test
    fun aDeeplyNestedProjectIsPacked() {
        val src = tmp.newFolder("deep")
        // Short names: the depth matters, not the path length (Windows hosts cap paths).
        var dir = src
        repeat(60) { dir = File(dir, "d").apply { mkdir() } }
        File(dir, "leaf.py").writeText("x = 1\n")
        assertTrue(entries(zipFolder(src, tmp.newFolder("out"))).any { it.endsWith("/leaf.py") })
    }

    @Test
    fun aProjectOverTheLimitIsRefusedAndLeavesNothingBehind() {
        val src = project()
        File(src, "big.bin").writeBytes(ByteArray(10_000))
        val out = tmp.newFolder("out")
        try {
            zipFolder(src, out, maxBytes = 5_000)
            fail("expected ZipTooLargeException")
        } catch (e: ZipTooLargeException) {
            assertEquals(5_000L, e.limitBytes)
        }
        assertTrue(out.walkTopDown().none { it.isFile })
    }

    @Test
    fun cancellingMidwayLeavesNoPartFile() {
        val src = project()
        val out = tmp.newFolder("out")
        var calls = 0
        try {
            zipFolder(src, out) { if (++calls == 3) throw CancellationException("left the screen") }
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(out.walkTopDown().none { it.isFile })
    }

    @Test
    fun mimeTypesFollowTheExtension() {
        assertEquals("text/x-python", mimeTypeFor(File("a.py")))
        assertEquals("application/zip", mimeTypeFor(File("a.zip")))
        assertEquals("text/plain", mimeTypeFor(File("notes.txt")))
        assertEquals("application/json", mimeTypeFor(File("data.JSON")))
        assertEquals("application/octet-stream", mimeTypeFor(File("blob.bin")))
    }

    @Test
    fun imageMimeTypes() {
        assertEquals("image/png", mimeTypeFor(File("plot.PNG")))
        assertEquals("image/jpeg", mimeTypeFor(File("a.jpeg")))
        assertEquals("image/gif", mimeTypeFor(File("a.gif")))
        assertEquals("image/webp", mimeTypeFor(File("a.webp")))
        assertEquals("image/bmp", mimeTypeFor(File("a.bmp")))
        assertEquals("image/svg+xml", mimeTypeFor(File("logo.svg")))
        assertEquals("application/pdf", mimeTypeFor(File("doc.pdf")))
    }
}
