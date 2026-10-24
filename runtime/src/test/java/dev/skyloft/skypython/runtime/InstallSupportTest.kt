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

    /** A little-endian ELF header; [elfClass] 1 = 32-bit, 2 = 64-bit. */
    private fun elf(machine: Int, elfClass: Int = 2): File {
        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), elfClass.toByte(), 1, 1))
        header.position(18)
        header.putShort(machine.toShort())
        return tmp.newFile().apply { writeBytes(header.array()) }
    }

    @Test
    fun elfAbiDetects64BitAbis() {
        assertEquals("arm64-v8a", InstallSupport.elfAbi(elf(183)))
        assertEquals("x86_64", InstallSupport.elfAbi(elf(62)))
        assertNull(InstallSupport.elfAbi(tmp.newFile().apply { writeText("not an elf") }))
        assertNull(InstallSupport.elfAbi(File(tmp.root, "missing.so")))
    }

    @Test
    fun elfAbiDetects32BitAbis() {
        assertEquals("armeabi-v7a", InstallSupport.elfAbi(elf(machine = 40, elfClass = 1)))
        assertEquals("x86", InstallSupport.elfAbi(elf(machine = 3, elfClass = 1)))
    }

    @Test
    fun elfAbiRejectsMachineWithWrongClass() {
        // EM_ARM in a 64-bit file, EM_AARCH64 in a 32-bit one: not a real Android ABI.
        assertNull(InstallSupport.elfAbi(elf(machine = 40, elfClass = 2)))
        assertNull(InstallSupport.elfAbi(elf(machine = 183, elfClass = 1)))
        assertNull(InstallSupport.elfAbi(elf(machine = 8, elfClass = 1))) // MIPS
    }

    @Test
    fun parseAbisSplitsBuildConfigList() {
        assertEquals(setOf("arm64-v8a", "x86_64"), InstallSupport.parseAbis("arm64-v8a,x86_64"))
        assertEquals(
            setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
            InstallSupport.parseAbis(" arm64-v8a, armeabi-v7a,,x86 ,x86_64"),
        )
        assertEquals(emptySet<String>(), InstallSupport.parseAbis(""))
    }

    @Test
    fun chooseAbiPrefersRunningLibraryThenSupportedAbis() {
        val shipped = setOf("arm64-v8a", "x86_64")
        assertEquals("x86_64", InstallSupport.chooseAbi("x86_64", arrayOf("arm64-v8a"), shipped))
        assertEquals("arm64-v8a", InstallSupport.chooseAbi(null, arrayOf("armeabi-v7a", "arm64-v8a"), shipped))
        assertNull(InstallSupport.chooseAbi(null, arrayOf("armeabi-v7a", "x86"), shipped))
    }

    @Test
    fun chooseAbiPicks32BitWhenShipped() {
        val all = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        // 32-bit-only phone.
        assertEquals("armeabi-v7a", InstallSupport.chooseAbi(null, arrayOf("armeabi-v7a", "armeabi"), all))
        // The package manager extracted the 32-bit libpython (e.g. a 32-bit-only split installed).
        assertEquals("armeabi-v7a", InstallSupport.chooseAbi("armeabi-v7a", arrayOf("arm64-v8a", "armeabi-v7a"), all))
        assertEquals("x86", InstallSupport.chooseAbi(null, arrayOf("x86"), all))
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
    fun isInstalledComparesMarker() {
        val target = File(tmp.root, "python")
        assertFalse(InstallSupport.isInstalled(target, ".installed", "v1"))
        File(target, ".installed").apply { parentFile!!.mkdirs(); writeText("v1") }
        assertTrue(InstallSupport.isInstalled(target, ".installed", "v1"))
        assertFalse(InstallSupport.isInstalled(target, ".installed", "v2"))
    }
}
