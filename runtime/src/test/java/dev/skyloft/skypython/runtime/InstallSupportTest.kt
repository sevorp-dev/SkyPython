package dev.skyloft.skypython.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallSupportTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun elf(machine: Int): File {
        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        header.position(18)
        header.putShort(machine.toShort())
        return tmp.newFile().apply { writeBytes(header.array()) }
    }

    @Test
    fun elfAbiDetectsShippedAbis() {
        assertEquals("arm64-v8a", InstallSupport.elfAbi(elf(183)))
        assertEquals("x86_64", InstallSupport.elfAbi(elf(62)))
        assertNull(InstallSupport.elfAbi(elf(40))) // 32-bit ARM: not shipped
        assertNull(InstallSupport.elfAbi(tmp.newFile().apply { writeText("not an elf") }))
        assertNull(InstallSupport.elfAbi(File(tmp.root, "missing.so")))
    }

    @Test
    fun chooseAbiPrefersRunningLibraryThenSupportedAbis() {
        val shipped = setOf("arm64-v8a", "x86_64")
        assertEquals("x86_64", InstallSupport.chooseAbi("x86_64", arrayOf("arm64-v8a"), shipped))
        assertEquals("arm64-v8a", InstallSupport.chooseAbi(null, arrayOf("armeabi-v7a", "arm64-v8a"), shipped))
        assertNull(InstallSupport.chooseAbi(null, arrayOf("armeabi-v7a", "x86"), shipped))
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun installReplacesTargetAtomicallyWithMarker() {
        val target = File(tmp.root, "python")
        File(target, "lib/python3.14/stale.py").apply { parentFile!!.mkdirs(); writeText("old") }

        InstallSupport.install(
            zip("lib/" to "", "lib/python3.14/os.py" to "import sys").inputStream(),
            target,
            marker = ".installed",
            markerContent = "3.14.9 x86_64",
        )

        assertEquals("import sys", File(target, "lib/python3.14/os.py").readText())
        assertFalse(File(target, "lib/python3.14/stale.py").exists())
        assertEquals("3.14.9 x86_64", File(target, ".installed").readText())
        assertEquals(listOf("python"), tmp.root.list()!!.filter { !it.startsWith("junit") })
    }

    @Test
    fun installRejectsZipSlipAndLeavesExistingInstallUntouched() {
        val target = File(tmp.root, "python")
        File(target, ".installed").apply { parentFile!!.mkdirs(); writeText("old") }
        try {
            InstallSupport.install(zip("../evil.py" to "x").inputStream(), target, ".installed", "new")
            fail("expected IOException")
        } catch (expected: IOException) {
        }
        assertFalse(File(tmp.root, "evil.py").exists())
        assertEquals("old", File(target, ".installed").readText())
        assertTrue(tmp.root.list()!!.none { it.startsWith("python.") })
    }

    @Test
    fun requireFreeSpaceFailsWithReadableMessageWhenStorageIsLow() {
        val mb = 1024L * 1024
        try {
            InstallSupport.requireFreeSpace(tmp.root, requiredBytes = 35 * mb, usableBytes = 12 * mb)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(
                "Not enough free storage to install Python: 35 MB needed, 12 MB free. " +
                    "Free up some space and try again.",
                e.message,
            )
        }
    }

    @Test
    fun requireFreeSpacePassesWhenEnoughIsFree() {
        val mb = 1024L * 1024
        InstallSupport.requireFreeSpace(tmp.root, requiredBytes = 35 * mb, usableBytes = 35 * mb)
        InstallSupport.requireFreeSpace(tmp.root, requiredBytes = 35 * mb) // real disk of the build host
    }

    @Test
    fun isInstalledComparesMarker() {
        val target = File(tmp.root, "python")
        assertFalse(InstallSupport.isInstalled(target, ".installed", "v1"))
        File(target, ".installed").apply { parentFile!!.mkdirs(); writeText("v1") }
        assertTrue(InstallSupport.isInstalled(target, ".installed", "v1"))
        assertFalse(InstallSupport.isInstalled(target, ".installed", "v2"))
    }
}
