package dev.skyloft.skypython.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/** Android-independent parts of [PythonInstaller], unit-tested on the JVM. */
internal object InstallSupport {

    /** ABIs we ship a CPython build for. */
    val SHIPPED_ABIS: Set<String> = setOf("arm64-v8a", "x86_64")

    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183

    /** ABI of a 64-bit little-endian ELF file, or null if unknown / not shipped / unreadable. */
    fun elfAbi(file: File): String? = try {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(20)
            raf.readFully(header)
            val isElf64Le = header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[4] == 2.toByte() && header[5] == 1.toByte()
            if (!isElf64Le) return null
            when ((header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)) {
                EM_AARCH64 -> "arm64-v8a"
                EM_X86_64 -> "x86_64"
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
