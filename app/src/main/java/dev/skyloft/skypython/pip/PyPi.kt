package dev.skyloft.skypython.pip

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Whether the latest release can be installed on the device without a compiler. */
enum class WheelSupport {
    /** A `py3-none-any` (or similar) wheel: installs anywhere. */
    Pure,
    /** A wheel built for Android and this Python (64-bit devices only, for now). */
    PrebuiltAndroid,
    /** Only a source archive: pip builds it, which works only if it's pure Python. */
    SourceOnly,
    /** Wheels for other platforms only: needs native code, not supported yet. */
    NativeOnly,
    /** The latest release has no files at all. */
    NoFiles,
}

/** The latest release of a PyPI project, as the Search tab shows it. */
data class PyPiProject(
    val name: String,
    val version: String,
    val summary: String?,
    val homePage: String?,
    val license: String?,
    val wheel: WheelSupport,
)

/** Reads PyPI's JSON API (`/pypi/<name>/json`). */
object PyPiJson {

    private const val MAX_LICENSE_CHARS = 60
    private val SEPARATORS = Regex("[-_.]+")
    private val HOME_KEYS = listOf("homepage", "home", "home page", "source", "source code", "repository", "code")

    /** PEP 503 normalized name: `Python_DateUtil` → `python-dateutil`. */
    fun normalize(name: String): String = name.replace(SEPARATORS, "-").lowercase()

    fun parse(json: String): PyPiProject? = parse(StringReader(json))

    /**
     * Streams the document: the `releases` map of a big project runs to megabytes and is
     * skipped without being built. Null if it's not a project document.
     */
    fun parse(reader: Reader): PyPiProject? = try {
        val r = JsonReader(reader)
        var info: JsonObject? = null
        var files: List<String>? = null
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw IllegalStateException("not an object")
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "info" -> info = JsonParser.parseReader(r).takeIf { it.isJsonObject }?.asJsonObject
                "urls" -> files = readFileNames(JsonParser.parseReader(r))
                else -> r.skipValue()
            }
        }
        r.endObject()
        info?.let { project(it, files.orEmpty()) }
    } catch (e: RuntimeException) {
        // Gson throws JsonParseException / IllegalStateException / NumberFormatException.
        null
    } catch (e: IOException) {
        null
    }

    private fun readFileNames(urls: JsonElement): List<String> =
        if (!urls.isJsonArray) emptyList()
        else urls.asJsonArray.mapNotNull { (it as? JsonObject)?.string("filename") }

    private fun project(info: JsonObject, files: List<String>): PyPiProject? {
        val name = info.string("name") ?: return null
        val version = info.string("version") ?: return null
        return PyPiProject(
            name = name,
            version = version,
            summary = info.string("summary"),
            homePage = homePage(info),
            license = license(info),
            wheel = wheelSupport(files),
        )
    }

    private fun homePage(info: JsonObject): String? {
        info.string("home_page")?.let { return it }
        val urls = info.get("project_urls")?.takeIf { it.isJsonObject }?.asJsonObject
        if (urls != null) {
            val byKey = urls.keySet().associateBy { it.lowercase() }
            for (key in HOME_KEYS) byKey[key]?.let { urls.string(it) }?.let { return it }
        }
        return info.string("package_url") ?: info.string("project_url")
    }

    private fun license(info: JsonObject): String? {
        info.string("license_expression")?.let { return it }
        val text = info.string("license")
        if (text != null && '\n' !in text && text.length <= MAX_LICENSE_CHARS) return text
        val classifier = info.get("classifiers")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            ?.firstOrNull { it.startsWith("License ::") }
            ?.substringAfterLast("::")?.trim()
        if (!classifier.isNullOrEmpty()) return classifier
        return text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_LICENSE_CHARS)
    }

    /** Classifies the files of one release by their names (see [WheelSupport]). */
    fun wheelSupport(filenames: List<String>): WheelSupport {
        if (filenames.isEmpty()) return WheelSupport.NoFiles
        val wheels = filenames.filter { it.endsWith(".whl", ignoreCase = true) }
        return when {
            wheels.any { it.endsWith("-none-any.whl", ignoreCase = true) } -> WheelSupport.Pure
            wheels.any { isAndroidWheelForUs(it) } -> WheelSupport.PrebuiltAndroid
            wheels.isEmpty() -> WheelSupport.SourceOnly
            else -> WheelSupport.NativeOnly
        }
    }

    /** `name-ver-cp314-cp314-android_24_arm64_v8a.whl` or a stable-ABI (`abi3`) Android wheel. */
    private fun isAndroidWheelForUs(filename: String): Boolean {
        val tags = filename.removeSuffix(".whl").split('-').takeLast(3)
        if (tags.size != 3) return false
        val (python, abi, platform) = tags
        if (!platform.startsWith("android_")) return false
        return python.split('.').any { it == PYTHON_TAG } || abi == "abi3"
    }

    /** The bundled interpreter. */
    private const val PYTHON_TAG = "cp314"

    private fun JsonObject.string(key: String): String? {
        val e = get(key) ?: return null
        if (!e.isJsonPrimitive) return null
        return e.asString.trim().takeIf { it.isNotEmpty() && it != "UNKNOWN" }
    }
}

sealed interface LookupResult {
    data class Found(val project: PyPiProject) : LookupResult
    data class NotFound(val name: String) : LookupResult
    /** No network (or PyPI unreachable). */
    data class Offline(val message: String) : LookupResult
    data class Failed(val message: String) : LookupResult
}

/** Looks a name up on the package index; [PyPiClient] is the real one. */
fun interface PackageIndex {
    suspend fun lookup(name: String): LookupResult
}

/** An HTTP status and, for 200, the parsed project (null if the body wasn't one). */
data class HttpReply(val code: Int, val project: PyPiProject? = null)

/**
 * PyPI has no search API, so this is an exact lookup by (normalized) name through the JSON
 * API. [fetch] does the blocking request; it runs on [io].
 */
class PyPiClient(
    private val baseUrl: String = "https://pypi.org/pypi",
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fetch: (String) -> HttpReply = ::httpGet,
) : PackageIndex {

    override suspend fun lookup(name: String): LookupResult {
        if (!PackageSpec.isName(name)) return LookupResult.NotFound(name)
        val url = "$baseUrl/${PyPiJson.normalize(name)}/json"
        return try {
            val reply = withContext(io) { fetch(url) }
            when {
                reply.code == HttpURLConnection.HTTP_NOT_FOUND -> LookupResult.NotFound(name)
                reply.code != HttpURLConnection.HTTP_OK -> LookupResult.Failed("HTTP ${reply.code}")
                else -> reply.project?.let { LookupResult.Found(it) } ?: LookupResult.Failed("Unexpected reply from PyPI")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            LookupResult.Offline(e.message ?: e.toString())
        } catch (e: ConnectException) {
            LookupResult.Offline(e.message ?: e.toString())
        } catch (e: NoRouteToHostException) {
            LookupResult.Offline(e.message ?: e.toString())
        } catch (e: SocketTimeoutException) {
            LookupResult.Offline(e.message ?: e.toString())
        } catch (e: IOException) {
            LookupResult.Failed(e.message ?: e.toString())
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        /** GET [url]; a 200 body is stream-parsed by [PyPiJson]. */
        fun httpGet(url: String): HttpReply {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "SkyPython")
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) return HttpReply(code)
                val project = conn.inputStream.bufferedReader(Charsets.UTF_8).use { PyPiJson.parse(it) }
                return HttpReply(code, project)
            } finally {
                conn.disconnect()
            }
        }
    }
}
