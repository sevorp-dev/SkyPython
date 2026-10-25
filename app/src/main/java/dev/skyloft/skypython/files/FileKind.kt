package dev.skyloft.skypython.files

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * How a file in Files opens: [Text] in the editor, [Image] in the image viewer, [Binary] nowhere
 * in the app ("Can't open this file type" with Share / Open with…).
 */
enum class FileKind {
    Text, Image, Binary;

    companion object {
        /** What the image viewer can show (Android decodes these; a GIF shows its first frame). */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        private val TEXT_EXTENSIONS = setOf(
            "py", "pyw", "pyi", "txt", "md", "rst", "json", "csv", "tsv", "xml", "html", "htm", "css",
            "js", "ts", "svg", "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "sh", "bat",
            "c", "h", "cpp", "hpp", "java", "kt", "kts", "go", "rs", "rb", "lua", "sql", "tex", "env",
        )

        private val TEXT_NAMES = setOf("makefile", "dockerfile", "license", "readme", "procfile", ".gitignore", ".env")

        private val BINARY_EXTENSIONS = setOf(
            "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar", "whl", "egg", "jar", "apk", "aab",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
            "mp3", "wav", "ogg", "flac", "m4a", "aac", "mp4", "mkv", "avi", "mov", "webm", "3gp",
            "so", "o", "a", "dll", "exe", "bin", "dex", "class", "pyc", "pyo", "pyd",
            "db", "sqlite", "sqlite3", "npy", "npz", "pkl", "pickle", "h5", "hdf5", "parquet",
            "ico", "tif", "tiff", "heic", "heif", "psd", "ttf", "otf", "woff", "woff2",
        )

        /** How many bytes [of] reads to decide a file with an unknown extension. */
        private const val SNIFF_BYTES = 4096

        /** The kind told by the name alone, or null when only the content can tell. */
        fun byName(name: String): FileKind? {
            val lower = name.lowercase()
            if (lower in TEXT_NAMES) return Text
            val ext = lower.substringAfterLast('.', missingDelimiterValue = "")
            return when {
                ext.isEmpty() -> null
                ext in IMAGE_EXTENSIONS -> Image
                ext in TEXT_EXTENSIONS -> Text
                ext in BINARY_EXTENSIONS -> Binary
                else -> null
            }
        }

        /** The kind of [file]: by its name, else by its first bytes (reads the disk). */
        fun of(file: File): FileKind {
            byName(file.name)?.let { return it }
            val head = try {
                file.inputStream().use { input ->
                    val buf = ByteArray(SNIFF_BYTES)
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
            } catch (_: IOException) {
                // Unreadable: let the editor say why.
                return Text
            }
            return if (looksBinary(head)) Binary else Text
        }

        /** True if [bytes] (the start of a file) is not UTF-8 text: a NUL byte or a broken sequence. */
        fun looksBinary(bytes: ByteArray): Boolean {
            if (bytes.any { it == 0.toByte() }) return true
            // A character cut by the end of the sample is fine: drop up to 3 trailing bytes of it.
            val end = completeUtf8Length(bytes)
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes, 0, end))
                false
            } catch (_: CharacterCodingException) {
                true
            }
        }

        /** [bytes]' length without a multi-byte UTF-8 character that the end cut short. */
        private fun completeUtf8Length(bytes: ByteArray): Int {
            val n = bytes.size
            var i = n - 1
            var continuation = 0
            while (i >= 0 && continuation < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
                i--
                continuation++
            }
            if (i < 0) return n
            val lead = bytes[i].toInt() and 0xFF
            val need = when {
                lead and 0xE0 == 0xC0 -> 1
                lead and 0xF0 == 0xE0 -> 2
                lead and 0xF8 == 0xF0 -> 3
                else -> return n
            }
            return if (continuation < need) i else n
        }
    }
}
