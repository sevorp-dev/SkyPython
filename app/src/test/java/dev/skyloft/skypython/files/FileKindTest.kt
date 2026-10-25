package dev.skyloft.skypython.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileKindTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun imagesAreRecognisedByExtensionIgnoringCase() {
        for (name in listOf("plot.png", "a.jpg", "b.JPEG", "c.gif", "d.webp", "e.bmp", "F.PNG")) {
            assertEquals(name, FileKind.Image, FileKind.byName(name))
        }
    }

    @Test
    fun textFilesOpenInTheEditor() {
        for (name in listOf("main.py", "notes.txt", "data.json", "README.md", "page.html", "logo.svg", "requirements.txt", "Makefile", ".gitignore")) {
            assertEquals(name, FileKind.Text, FileKind.byName(name))
        }
    }

    @Test
    fun knownBinaryFilesAreNotText() {
        for (name in listOf("project.zip", "doc.pdf", "song.mp3", "clip.mp4", "lib.so", "mod.pyc", "pkg.whl", "app.apk", "db.sqlite3", "arch.tar.gz")) {
            assertEquals(name, FileKind.Binary, FileKind.byName(name))
        }
    }

    @Test
    fun unknownExtensionIsDecidedByContent() {
        val text = tmp.newFile("data.xyz").apply { writeText("hello\nwörld\n") }
        val bin = tmp.newFile("blob.xyz").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0, 0, 1, 2)) }
        val empty = tmp.newFile("empty.xyz")
        assertEquals(FileKind.Text, FileKind.of(text))
        assertEquals(FileKind.Binary, FileKind.of(bin))
        assertEquals(FileKind.Text, FileKind.of(empty))
    }

    @Test
    fun knownExtensionWinsOverContent() {
        // A .py with odd bytes still opens in the editor (it decodes what it can).
        val py = tmp.newFile("weird.py").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        assertEquals(FileKind.Text, FileKind.of(py))
        val png = tmp.newFile("fake.png").apply { writeText("not really") }
        assertEquals(FileKind.Image, FileKind.of(png))
    }

    @Test
    fun looksBinarySpotsNulBytesAndBrokenUtf8() {
        assertTrue(FileKind.looksBinary(byteArrayOf(1, 0, 2)))
        assertTrue(FileKind.looksBinary(byteArrayOf(0xC3.toByte(), 0x28)))
        assertFalse(FileKind.looksBinary("print('привет')\n".toByteArray()))
        assertFalse(FileKind.looksBinary(ByteArray(0)))
        // A multi-byte character cut by the sample's end is not "broken".
        val cut = "ab€".toByteArray().let { it.copyOf(it.size - 1) }
        assertFalse(FileKind.looksBinary(cut))
    }
}
