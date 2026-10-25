package dev.skyloft.skypython.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Why a download failed; the UI words it. */
class UpdateException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { Network, Http, NoChecksum, Checksum, Storage }
}

/**
 * Downloads a release's APK into [dir] (the app's cache) and checks it against the release's
 * `SHA256SUMS`. An interrupted download is kept as `<name>.part` and resumed with a `Range`
 * request; the APK only gets its real name once its SHA-256 matches.
 */
class UpdateDownloader(
    private val http: UpdateHttp,
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun download(version: SemVer, variant: String, progress: suspend (done: Long, total: Long) -> Unit): File =
        withContext(io) {
            val name = Releases.apkName(version, variant)
            val expected = expectedSha(version, name)
            val target = File(dir, name)
            val part = File(dir, "$name.part")
            if (!dir.isDirectory && !dir.mkdirs()) throw UpdateException(UpdateException.Kind.Storage, "Cannot create $dir")
            // Older downloads are no use any more.
            dir.listFiles()?.filter { it.name != name && it.name != part.name }?.forEach { it.deleteRecursively() }
            if (target.isFile && sha256(target) == expected) {
                progress(target.length(), target.length())
                return@withContext target
            }
            target.delete()

            var restarted = false
            while (true) {
                val offset = if (part.isFile) part.length() else 0L
                val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap()
                val again = try {
                    http.get(Releases.apkUrl(version, variant), headers).use { reply ->
                        val append = when (reply.code) {
                            206 -> offset > 0
                            200 -> false
                            416 -> {
                                part.delete()
                                if (restarted) throw UpdateException(UpdateException.Kind.Http, "HTTP 416 for $name")
                                restarted = true
                                return@use true
                            }
                            else -> throw UpdateException(UpdateException.Kind.Http, "HTTP ${reply.code} for $name")
                        }
                        val start = if (append) offset else 0L
                        val length = reply.headers["content-length"]?.toLongOrNull()
                        val total = if (length != null) start + length else -1L
                        val body = reply.body ?: throw UpdateException(UpdateException.Kind.Http, "Empty reply for $name")
                        copy(body, part, append, start, total, progress)
                        false
                    }
                } catch (e: UpdateException) {
                    throw e
                } catch (e: IOException) {
                    throw UpdateException(UpdateException.Kind.Network, e.message ?: e.javaClass.simpleName)
                }
                if (!again) break
            }
            val actual = sha256(part)
            if (actual != expected) {
                part.delete()
                throw UpdateException(UpdateException.Kind.Checksum, "SHA-256 of $name is $actual, expected $expected")
            }
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) throw UpdateException(UpdateException.Kind.Storage, "Cannot move $name into place")
            }
            target
        }

    /** Deletes every downloaded or half-downloaded APK. */
    fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun expectedSha(version: SemVer, name: String): String {
        val text = try {
            http.get(Releases.sumsUrl(version)).use { reply ->
                if (reply.code != 200) throw UpdateException(UpdateException.Kind.NoChecksum, "HTTP ${reply.code} for SHA256SUMS")
                reply.body?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(UpdateException.Kind.Network, e.message ?: e.javaClass.simpleName)
        }
        return Sha256Sums.parse(text)[name]
            ?: throw UpdateException(UpdateException.Kind.NoChecksum, "SHA256SUMS has no line for $name")
    }

    private suspend fun copy(
        body: InputStream,
        part: File,
        append: Boolean,
        start: Long,
        total: Long,
        progress: suspend (Long, Long) -> Unit,
    ) {
        var done = start
        var reported = -1L
        progress(done, total)
        FileOutputStream(part, append).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = body.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                // About every 256 KB: smooth enough, without flooding the UI.
                if (done - reported >= 256 * 1024) {
                    reported = done
                    progress(done, total)
                }
            }
            out.fd.sync()
        }
        progress(done, if (total < 0) done else total)
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
