package dev.skyloft.skypython.update

import java.io.ByteArrayInputStream
import java.io.IOException

/** Canned replies by URL; records every request. A missing URL is a 404. */
class FakeUpdateHttp : UpdateHttp {
    val heads = mutableMapOf<String, Pair<Int, String?>>()
    val files = mutableMapOf<String, ByteArray>()
    val failing = mutableSetOf<String>()
    /** Serve only the first N bytes of a file once, then drop the connection (an interrupted download). */
    val cutAfter = mutableMapOf<String, Int>()
    var honourRanges = true
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    override fun head(url: String): UpdateHead {
        requests += url to emptyMap()
        if (url in failing) throw IOException("offline")
        val (code, location) = heads[url] ?: (404 to null)
        return UpdateHead(code, location)
    }

    override fun get(url: String, headers: Map<String, String>): UpdateResponse {
        requests += url to headers
        if (url in failing) throw IOException("offline")
        val bytes = files[url] ?: return UpdateResponse(404)
        val range = headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
        if (range != null && honourRanges) {
            if (range >= bytes.size) return UpdateResponse(416)
            val rest = bytes.copyOfRange(range, bytes.size)
            return UpdateResponse(206, mapOf("content-length" to rest.size.toString()), ByteArrayInputStream(rest))
        }
        val cut = cutAfter.remove(url)
        val body = if (cut != null) CuttingStream(bytes, cut) else ByteArrayInputStream(bytes)
        return UpdateResponse(200, mapOf("content-length" to bytes.size.toString()), body)
    }

    private class CuttingStream(private val bytes: ByteArray, private val cut: Int) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos >= cut) throw IOException("connection reset")
            return bytes[pos++].toInt() and 0xff
        }
    }
}
