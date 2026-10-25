package dev.skyloft.skypython.update

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The answer to a HEAD request, redirects not followed. */
data class UpdateHead(val code: Int, val location: String?)

/** [headers] have lowercase names; [body] is null for bodiless replies. */
class UpdateResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: InputStream? = null,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            body?.close()
        } finally {
            onClose()
        }
    }
}

/** The updater's network seam (a fake one in tests). Calls block: run them off the main thread. */
interface UpdateHttp {
    /** HEAD [url] without following a redirect (the `/releases/latest` trick). */
    fun head(url: String): UpdateHead

    /** GET [url], following redirects (release files are served from another host). */
    fun get(url: String, headers: Map<String, String> = emptyMap()): UpdateResponse
}

/** [HttpURLConnection]: no GitHub API, so no rate limit and no token. */
object UrlUpdateHttp : UpdateHttp {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", "SkyPython")
        // Byte ranges must be of the file itself, not of a gzip stream.
        setRequestProperty("Accept-Encoding", "identity")
    }

    override fun head(url: String): UpdateHead {
        val conn = open(url)
        try {
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            return UpdateHead(conn.responseCode, conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }

    override fun get(url: String, headers: Map<String, String>): UpdateResponse {
        val conn = open(url)
        try {
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val replyHeaders = conn.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.lastOrNull().orEmpty() }
            val body = if (code in 200..299) conn.inputStream else null
            return UpdateResponse(code, replyHeaders, body) { conn.disconnect() }
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }
}
