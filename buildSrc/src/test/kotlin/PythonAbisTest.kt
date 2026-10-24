import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Properties

class PythonAbisTest {

    private fun assertFails(messagePart: String, block: () -> Unit) {
        try {
            block()
            fail("expected failure containing '$messagePart'")
        } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should contain '$messagePart'", e.message!!.contains(messagePart))
        }
    }

    @Test
    fun mapsEveryAbiToItsCPythonHostTriple() {
        assertEquals("aarch64-linux-android", PythonAbis.triple("arm64-v8a"))
        assertEquals("arm-linux-androideabi", PythonAbis.triple("armeabi-v7a"))
        assertEquals("i686-linux-android", PythonAbis.triple("x86"))
        assertEquals("x86_64-linux-android", PythonAbis.triple("x86_64"))
        assertFails("mips") { PythonAbis.triple("mips") }
    }

    @Test
    fun parseDefaultsTo64BitOnly() {
        assertEquals(listOf("arm64-v8a", "x86_64"), PythonAbis.parse(null))
        assertEquals(listOf("arm64-v8a", "x86_64"), PythonAbis.parse("  "))
    }

    @Test
    fun parseTrimsKeepsOrderAndDropsDuplicates() {
        assertEquals(
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
            PythonAbis.parse(" arm64-v8a, armeabi-v7a ,x86,x86_64,,x86 "),
        )
        assertEquals(listOf("armeabi-v7a"), PythonAbis.parse("armeabi-v7a"))
    }

    @Test
    fun parseRejectsUnknownAbis() {
        assertFails("armeabi") { PythonAbis.parse("arm64-v8a,armeabi") }
    }

    @Test
    fun officialBuildsFor64BitOwnReleaseFor32Bit() {
        assertEquals("python-3.14.9-aarch64-linux-android.tar.gz", PythonAbis.tarballName("arm64-v8a", "3.14.9"))
        assertEquals(
            "https://www.python.org/ftp/python/3.14.9/python-3.14.9-x86_64-linux-android.tar.gz",
            PythonAbis.tarballUrl("x86_64", "3.14.9"),
        )
        assertEquals(
            "https://github.com/sevorp-dev/SkyPython/releases/download/python-android-3.14.9/" +
                "python-3.14.9-arm-linux-androideabi.tar.gz",
            PythonAbis.tarballUrl("armeabi-v7a", "3.14.9"),
        )
        assertEquals(
            "https://github.com/sevorp-dev/SkyPython/releases/download/python-android-3.14.9/" +
                "python-3.14.9-i686-linux-android.tar.gz",
            PythonAbis.tarballUrl("x86", "3.14.9"),
        )
    }

    @Test
    fun sha256ReadsPinnedHashByTriple() {
        val hash = "a".repeat(64)
        val props = Properties().apply { setProperty("sha256.i686-linux-android", " ${hash.uppercase()} ") }
        assertEquals(hash, PythonAbis.sha256(props, "x86"))
    }

    @Test
    fun sha256FailsClearlyForPlaceholderMissingOrMalformed() {
        val props = Properties().apply {
            setProperty("sha256.arm-linux-androideabi", PythonAbis.PLACEHOLDER)
            setProperty("sha256.i686-linux-android", "1234")
        }
        assertFails("build-python") { PythonAbis.sha256(props, "armeabi-v7a") }
        assertFails("sha256.i686-linux-android") { PythonAbis.sha256(props, "x86") }
        assertFails("sha256.aarch64-linux-android") { PythonAbis.sha256(props, "arm64-v8a") }
    }
}
