package dev.skyloft.skypython.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/** Android-independent parts of [PythonInstaller], unit-tested on the JVM. */
internal object InstallSupport {

    /** Parses BuildConfig.PYTHON_ABIS ("arm64-v8a,x86_64"): the ABIs this APK ships Python for. */
    fun parseAbis(list: String): Set<String> =
        list.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private const val ELFCLASS32 = 1
    private const val ELFCLASS64 = 2
    private const val EM_386 = 3
    private const val EM_ARM = 40
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183

    /** Android ABI of a little-endian ELF file, or null if unknown / unreadable. */
    fun elfAbi(file: File): String? = try {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(20)
            raf.readFully(header)
            val isElfLe = header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[5] == 1.toByte()
            if (!isElfLe) return null
            // e_machine is at offset 18 in both ELF32 and ELF64 headers.
            val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            when (header[4].toInt() to machine) {
                ELFCLASS64 to EM_AARCH64 -> "arm64-v8a"
                ELFCLASS64 to EM_X86_64 -> "x86_64"
                ELFCLASS32 to EM_ARM -> "armeabi-v7a"
                ELFCLASS32 to EM_386 -> "x86"
                else -> null
            }
        }
    } catch (e: IOException) {
        null
    }

    /**
     * The ABI whose stdlib must be installed: the ABI of the libpython the package manager
     * actually extracted ([runningAbi]) if known, else the first shipped [supportedAbis] entry
     * (the package manager picks native libs in that order).
     */
    fun chooseAbi(runningAbi: String?, supportedAbis: Array<String>, shipped: Set<String>): String? =
        runningAbi?.takeIf { it in shipped } ?: supportedAbis.firstOrNull { it in shipped }

    fun isInstalled(target: File, marker: String, markerContent: String): Boolean {
        val file = File(target, marker)
        return file.isFile && file.readText() == markerContent
    }

    /**
     * Unpacks [zip] to a staging directory next to [target], writes [marker], then swaps it
     * into place with renames, so [target] is either the old install or the complete new one.
     */
    fun install(zip: InputStream, target: File, marker: String, markerContent: String) {
        val parent = target.absoluteFile.parentFile ?: throw IOException("No parent for $target")
        parent.mkdirs()
        // Leftovers from an interrupted earlier attempt.
        parent.listFiles()?.filter {
            it.name.startsWith("${target.name}.tmp-") || it.name.startsWith("${target.name}.old-")
        }?.forEach { it.deleteRecursively() }

        val stamp = System.nanoTime()
        val staging = File(parent, "${target.name}.tmp-$stamp")
        try {
            unzip(zip, staging)
            File(staging, marker).writeText(markerContent)

            val old = File(parent, "${target.name}.old-$stamp")
            if (target.exists() && !target.renameTo(old)) {
                throw IOException("Cannot move $target aside")
            }
            if (!staging.renameTo(target)) {
                old.renameTo(target)
                throw IOException("Cannot move $staging to $target")
            }
            old.deleteRecursively()
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun unzip(zip: InputStream, dest: File) {
        if (!dest.mkdirs()) throw IOException("Cannot create $dest")
        val root = dest.canonicalPath + File.separator
        ZipInputStream(zip.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(root)) {
                    throw IOException("Zip entry outside target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it, 64 * 1024) }
                }
            }
        }
    }
}
