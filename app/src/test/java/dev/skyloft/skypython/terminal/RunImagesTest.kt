package dev.skyloft.skypython.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunImagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun File.write(name: String, bytes: Int = 10, mtime: Long? = null): File =
        File(this, name).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(bytes))
            if (mtime != null) setLastModified(mtime)
        }

    @Test
    fun newImageIsFound() {
        val dir = tmp.newFolder("proj")
        dir.write("main.py")
        val before = RunImages.snapshot(dir)
        val png = dir.write("plot.png")
        assertEquals(listOf(png), RunImages.changed(before, RunImages.snapshot(dir)))
    }

    @Test
    fun untouchedImagesAndOtherFilesAreIgnored() {
        val dir = tmp.newFolder("proj")
        dir.write("old.png", mtime = 1_000_000L)
        val before = RunImages.snapshot(dir)
        dir.write("notes.txt")
        dir.write("data.csv")
        assertEquals(emptyList<File>(), RunImages.changed(before, RunImages.snapshot(dir)))
    }

    @Test
    fun rewrittenImageIsFoundByTimeOrSize() {
        val dir = tmp.newFolder("proj")
        val a = dir.write("a.png", bytes = 10, mtime = 1_000_000L)
        val b = dir.write("b.jpg", bytes = 10, mtime = 1_000_000L)
        val before = RunImages.snapshot(dir)
        dir.write("a.png", bytes = 10, mtime = 2_000_000L)
        // Same second on a coarse clock, but the content grew.
        dir.write("b.jpg", bytes = 99, mtime = 1_000_000L)
        assertEquals(setOf(a, b), RunImages.changed(before, RunImages.snapshot(dir)).toSet())
    }

    @Test
    fun imagesInSubfoldersCountButHiddenAndCacheFoldersDoNot() {
        val dir = tmp.newFolder("proj")
        val before = RunImages.snapshot(dir)
        val out = dir.write("out/fig.png")
        dir.write(".hidden/x.png")
        dir.write("__pycache__/y.png")
        dir.write("a/b/c/too_deep.png")
        assertEquals(listOf(out), RunImages.changed(before, RunImages.snapshot(dir)))
    }

    @Test
    fun newestFirstAndLimited() {
        val dir = tmp.newFolder("proj")
        val before = RunImages.snapshot(dir)
        val files = (1..6).map { dir.write("p$it.png", mtime = 1_000_000L + it * 1000) }
        val found = RunImages.changed(before, RunImages.snapshot(dir), limit = 4)
        assertEquals(files.reversed().take(4), found)
    }

    @Test
    fun deletedImagesAreNotReported() {
        val dir = tmp.newFolder("proj")
        val gone = dir.write("gone.png")
        val before = RunImages.snapshot(dir)
        gone.delete()
        assertTrue(RunImages.changed(before, RunImages.snapshot(dir)).isEmpty())
    }

    @Test
    fun missingFolderIsEmpty() {
        assertTrue(RunImages.snapshot(File(tmp.root, "nope")).isEmpty())
    }
}
