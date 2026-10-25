package dev.skyloft.skypython.pip

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skyloft.skypython.runtime.PythonInstaller
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

/**
 * SkyPython's own index of prebuilt Android wheels (packages with native code, e.g. NumPy),
 * published on the `wheels` branch of the repository:
 *
 * ```
 * {"schema": 1, "python": "cp314", "packages": {"numpy": [{"version": "2.5.4", "abi": "arm64-v8a",
 *   "file": "numpy-2.5.4-cp314-cp314-android_24_arm64_v8a.whl", "sha256": "…", "size": 1234567,
 *   "requires": [], "summary": "…"}]}}
 * ```
 *
 * `abi` is the Android ABI name; `requires` names other packages of the same index that must be
 * installed with it. The wheel files sit next to `index.json`.
 */
data class IndexWheel(
    val name: String,
    val version: String,
    val abi: String,
    /** A plain file name ending in `.whl` (checked: never a path). */
    val file: String,
    /** Lowercase hex. */
    val sha256: String,
    val size: Long,
    val requires: List<String>,
    val summary: String?,
)

/** A parsed index; [packages] is keyed by normalized name ([PyPiJson.normalize]). */
data class WheelIndexData(val python: String, val packages: Map<String, List<IndexWheel>>) {

    fun entries(name: String): List<IndexWheel> = packages[PyPiJson.normalize(name)].orEmpty()

    /** The newest wheel of [name] for [abi] matching [spec] (`>=2,<3`; "" for any). */
    fun best(name: String, abi: String, spec: String = ""): IndexWheel? =
        entries(name).filter { it.abi == abi && Versions.matches(it.version, spec) }
            .maxWithOrNull { a, b -> Versions.compare(a.version, b.version) }

    /** How [request] (a [PackageSpec], e.g. `numpy==2.5.4`) installs from this index on [abi]. */
    fun plan(request: String, abi: String): WheelPlan {
        val parsed = Requirement.parse(request) ?: return WheelPlan.NotInIndex
        val all = entries(parsed.name)
        if (all.isEmpty()) return WheelPlan.NotInIndex
        val forAbi = all.filter { it.abi == abi }
        if (forAbi.isEmpty()) return WheelPlan.Unavailable(all.first().name, abi, all.map { it.abi }.toSet())
        val top = best(parsed.name, abi, parsed.spec)
            ?: return WheelPlan.NoVersion(
                all.first().name,
                parsed.spec,
                forAbi.map { it.version }.distinct().sortedWith { a, b -> Versions.compare(b, a) },
            )
        val ordered = LinkedHashMap<String, IndexWheel>()
        val visiting = mutableSetOf<String>()
        fun visit(wheel: IndexWheel): WheelPlan.Unavailable? {
            val key = PyPiJson.normalize(wheel.name)
            if (key in ordered || !visiting.add(key)) return null
            for (dep in wheel.requires) {
                val req = Requirement.parse(dep) ?: continue
                val depKey = PyPiJson.normalize(req.name)
                if (depKey in ordered || depKey in visiting) continue
                val candidates = entries(req.name)
                // Not in the index: pip gets it from PyPI.
                if (candidates.isEmpty()) continue
                val chosen = best(req.name, abi, req.spec)
                    ?: return WheelPlan.Unavailable(candidates.first().name, abi, candidates.map { it.abi }.toSet())
                visit(chosen)?.let { return it }
            }
            ordered[key] = wheel
            return null
        }
        visit(top)?.let { return it }
        return WheelPlan.Ready(ordered.values.toList(), parsed.extras)
    }
}

/** What installing a request from the index takes. */
sealed interface WheelPlan {
    /** Not a package of the index: plain pip. */
    data object NotInIndex : WheelPlan
    /** Download these (requirements first, the request last), then install them together. */
    data class Ready(val wheels: List<IndexWheel>, val extras: String = "") : WheelPlan
    /** [name] is in the index, but only for [abis], not for the device's [abi]. */
    data class Unavailable(val name: String, val abi: String, val abis: Set<String>) : WheelPlan
    /** No prebuilt [name] matches [spec]; [versions] are the ones there are, newest first. */
    data class NoVersion(val name: String, val spec: String, val versions: List<String>) : WheelPlan
}

/** What the Pip screen shows for a name. */
sealed interface PrebuiltStatus {
    data class Available(val wheel: IndexWheel) : PrebuiltStatus
    data class OtherAbis(val abis: Set<String>) : PrebuiltStatus
}

/** The index as it applies to this device's [abi]. */
data class WheelCatalog(val index: WheelIndexData, val abi: String) {
    /** Null when [name] isn't in the index at all. */
    fun status(name: String): PrebuiltStatus? {
        val all = index.entries(name)
        if (all.isEmpty()) return null
        return index.best(name, abi)?.let { PrebuiltStatus.Available(it) }
            ?: PrebuiltStatus.OtherAbis(all.map { it.abi }.toSet())
    }

    fun plan(request: String): WheelPlan = index.plan(request, abi)
}

/** `name[extras]<clauses>` split up; the clauses without spaces, e.g. `>=2,<3`. */
internal data class Requirement(val name: String, val extras: String, val spec: String) {
    companion object {
        private val REGEX = Regex("""^\s*([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)\s*(\[[^\]]*\])?\s*(.*?)\s*$""")
        fun parse(text: String): Requirement? {
            val m = REGEX.matchEntire(text) ?: return null
            val spec = m.groupValues[3].replace(" ", "")
            if (spec.isNotEmpty() && spec.first() !in "=!<>~") return null
            return Requirement(m.groupValues[1], m.groupValues[2], spec)
        }
    }
}

/**
 * Just enough PEP 440 for the index: numeric release segments compared as numbers, a
 * pre-release / dev suffix before its release, a post suffix after.
 */
object Versions {
    private val PARTS = Regex("""^v?(\d+(?:\.\d+)*)(.*)$""")

    fun compare(a: String, b: String): Int {
        val (ra, sa) = split(a)
        val (rb, sb) = split(b)
        for (i in 0 until maxOf(ra.size, rb.size)) {
            val c = ra.getOrElse(i) { 0L }.compareTo(rb.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return suffixRank(sa).compareTo(suffixRank(sb)).takeIf { it != 0 } ?: sa.compareTo(sb)
    }

    private fun suffixRank(s: String): Int = when {
        s.isEmpty() -> 1
        s.trimStart('.', '-', '_').startsWith("post") -> 2
        else -> 0
    }

    private fun split(v: String): Pair<List<Long>, String> {
        val m = PARTS.matchEntire(v.trim().lowercase()) ?: return emptyList<Long>() to v
        return m.groupValues[1].split('.').map { it.toLongOrNull() ?: 0L } to m.groupValues[2]
    }

    /** Whether [version] satisfies every comma-separated clause of [spec] ("" matches all). */
    fun matches(version: String, spec: String): Boolean =
        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.all { clause(version, it) }

    private fun clause(version: String, clause: String): Boolean {
        val op = listOf("===", "==", "!=", "<=", ">=", "~=", "<", ">").first { clause.startsWith(it) }
        val target = clause.removePrefix(op).trim()
        return when (op) {
            "===" -> version == target
            "==" -> equal(version, target)
            "!=" -> !equal(version, target)
            "<=" -> compare(version, target) <= 0
            ">=" -> compare(version, target) >= 0
            "<" -> compare(version, target) < 0
            ">" -> compare(version, target) > 0
            else -> { // ~=X.Y.Z: >= X.Y.Z and == X.Y.*
                val release = split(target).first
                compare(version, target) >= 0 &&
                    (release.size < 2 || equal(version, release.dropLast(1).joinToString(".") + ".*"))
            }
        }
    }

    private fun equal(version: String, target: String): Boolean {
        if (!target.endsWith(".*")) return compare(version, target) == 0
        val prefix = split(target.removeSuffix(".*")).first
        val release = split(version).first
        return prefix.indices.all { release.getOrElse(it) { 0L } == prefix[it] }
    }
}

/** Reads `index.json` (see [IndexWheel]); null if it isn't a schema-1 index for [python]. */
object WheelIndexJson {
    const val SCHEMA = 1
    /** The bundled interpreter. */
    const val PYTHON = "cp314"

    private val SHA256 = Regex("[0-9a-fA-F]{64}")
    private val FILE = Regex("""[A-Za-z0-9._+!-]+\.whl""")

    fun parse(json: String, python: String = PYTHON): WheelIndexData? = try {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        if (root == null || root.int("schema") != SCHEMA || root.str("python") != python) {
            null
        } else {
            val packages = root.get("packages")?.takeIf { it.isJsonObject }?.asJsonObject
            if (packages == null) {
                null
            } else {
                val map = LinkedHashMap<String, List<IndexWheel>>()
                for ((name, list) in packages.entrySet()) {
                    if (!PackageSpec.isName(name) || !list.isJsonArray) continue
                    val wheels = list.asJsonArray.mapNotNull { wheel(name, it) }
                    if (wheels.isNotEmpty()) map[PyPiJson.normalize(name)] = wheels
                }
                WheelIndexData(python, map)
            }
        }
    } catch (e: RuntimeException) {
        // Gson's JsonParseException / IllegalStateException / NumberFormatException.
        null
    }

    private fun wheel(name: String, e: JsonElement): IndexWheel? {
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val version = o.str("version") ?: return null
        val abi = o.str("abi") ?: return null
        val file = o.str("file")?.takeIf { FILE.matches(it) && it != ".whl" && !it.startsWith(".") } ?: return null
        val sha = o.str("sha256")?.takeIf { SHA256.matches(it) }?.lowercase() ?: return null
        val size = o.get("size")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            ?.takeIf { it >= 0 } ?: return null
        val requires = o.get("requires")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { r -> r.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        return IndexWheel(name, version, abi, file, sha, size, requires, o.str("summary"))
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
}

/** A downloaded wheel did not match the index: [file]'s sha256 is [actual], not [expected]. */
class WheelVerificationException(val file: String, val expected: String, val actual: String) :
    IOException("Checksum mismatch for $file: expected $expected, got $actual")

/** A GET; [WheelIndex]'s network seam. */
fun interface WheelHttp {
    /** [headers] go out as given; the caller closes the response. */
    fun get(url: String, headers: Map<String, String>): WheelResponse
}

/** [headers] have lowercase names; [body] is null for bodiless replies. */
class WheelResponse(
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

/** HttpURLConnection for http(s); `file:` URLs (tests, a local index) read the file. */
object UrlWheelHttp : WheelHttp {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    override fun get(url: String, headers: Map<String, String>): WheelResponse {
        if (url.startsWith("file:")) {
            val file = File(java.net.URI(url))
            return if (file.isFile) WheelResponse(200, emptyMap(), FileInputStream(file)) else WheelResponse(404)
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "SkyPython")
            // Byte ranges must be of the file itself, not of a gzip stream.
            conn.setRequestProperty("Accept-Encoding", "identity")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val replyHeaders = conn.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.lastOrNull().orEmpty() }
            val body = if (code in 200..299) conn.inputStream else null
            return WheelResponse(code, replyHeaders, body) { conn.disconnect() }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }
}

/** The prebuilt-wheel side of [PipRepository]; [WheelIndex] is the real one. */
interface PrebuiltWheels {
    /** Where downloaded wheels go; pip gets it as `--find-links`. */
    val wheelDir: File
    /** The index for this device, or null when there's none (not published, offline without a cache). */
    suspend fun catalog(): WheelCatalog?
    /** The index read from the server again, ignoring the cached copy's age. */
    suspend fun refresh(): WheelCatalog? = catalog()
    /** Downloads [wheel] into [wheelDir] and checks its sha256; returns the file. */
    suspend fun download(wheel: IndexWheel, progress: suspend (done: Long, total: Long) -> Unit): File
}

/**
 * Fetches and caches `index.json` from [baseUrl] and downloads wheels from beside it.
 *
 * The index is kept in `cacheDir/wheel-index` and trusted for [maxAgeMs]; after that it is
 * revalidated with `If-None-Match` / `If-Modified-Since`, and when that fails (offline) the
 * cached copy is used. Wheels are downloaded to `cacheDir/wheels/<file>.part` (resumed with a
 * `Range` request after an interruption), checked against the index's sha256 and size, and
 * only then renamed to `<file>`.
 *
 * @param abi the ABI of the interpreter (see [dev.skyloft.skypython.runtime.PythonPaths.abi]).
 */
class WheelIndex(
    baseUrl: String,
    cacheDir: File,
    private val abi: suspend () -> String,
    private val http: WheelHttp = UrlWheelHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxAgeMs: Long = 60 * 60 * 1000L,
    private val python: String = WheelIndexJson.PYTHON,
) : PrebuiltWheels {

    val baseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    override val wheelDir: File = File(cacheDir, "wheels")
    private val indexDir = File(cacheDir, "wheel-index")
    private val indexFile = File(indexDir, "index.json")
    private val metaFile = File(indexDir, "index.properties")

    private val lock = Mutex()
    private var memory: WheelIndexData? = null

    override suspend fun catalog(): WheelCatalog? {
        val data = load() ?: return null
        return WheelCatalog(data, abi())
    }

    override suspend fun refresh(): WheelCatalog? {
        val data = lock.withLock { withContext(io) { loadLocked(force = true) } } ?: return null
        return WheelCatalog(data, abi())
    }

    /** The index: cached if fresh, else revalidated; the cached copy when that fails; null if none. */
    suspend fun load(): WheelIndexData? = lock.withLock {
        withContext(io) { loadLocked() }
    }

    /** [force]: download the whole index even when the cached copy is fresh. */
    private fun loadLocked(force: Boolean = false): WheelIndexData? {
        val meta = readMeta()
        val fetchedAt = meta.getProperty(FETCHED_AT)?.toLongOrNull()
        val cached = memory ?: readCached()?.also { memory = it }
        if (!force && cached != null && fetchedAt != null && clock() - fetchedAt in 0 until maxAgeMs) return cached

        val headers = buildMap {
            if (cached != null && !force) {
                meta.getProperty(ETAG)?.let { put("If-None-Match", it) }
                meta.getProperty(LAST_MODIFIED)?.let { put("If-Modified-Since", it) }
            }
            put("Accept", "application/json")
        }
        return try {
            http.get(baseUrl + INDEX, headers).use { reply ->
                when (reply.code) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        if (cached != null) writeMeta(meta.apply { setProperty(FETCHED_AT, clock().toString()) })
                        cached
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val text = reply.body?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                        val parsed = WheelIndexJson.parse(text, python) ?: return cached
                        indexDir.mkdirs()
                        writeAtomically(indexFile, text.toByteArray(Charsets.UTF_8))
                        writeMeta(
                            Properties().apply {
                                setProperty(FETCHED_AT, clock().toString())
                                reply.headers["etag"]?.let { setProperty(ETAG, it) }
                                reply.headers["last-modified"]?.let { setProperty(LAST_MODIFIED, it) }
                            },
                        )
                        memory = parsed
                        parsed
                    }
                    // Not published (yet): nothing is prebuilt.
                    HttpURLConnection.HTTP_NOT_FOUND -> null
                    else -> cached
                }
            }
        } catch (e: IOException) {
            cached
        }
    }

    private fun readCached(): WheelIndexData? = try {
        if (indexFile.isFile) WheelIndexJson.parse(indexFile.readText(), python) else null
    } catch (e: IOException) {
        null
    }

    private fun readMeta(): Properties = Properties().apply {
        try {
            if (metaFile.isFile) metaFile.inputStream().use { load(it) }
        } catch (e: IOException) {
            clear()
        }
    }

    private fun writeMeta(meta: Properties) {
        indexDir.mkdirs()
        val bytes = java.io.ByteArrayOutputStream().also { meta.store(it, null) }.toByteArray()
        writeAtomically(metaFile, bytes)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Cannot write $target")
        }
    }

    override suspend fun download(wheel: IndexWheel, progress: suspend (done: Long, total: Long) -> Unit): File {
        require(wheel.file.endsWith(".whl") && '/' !in wheel.file && '\\' !in wheel.file) { "Bad wheel file name: ${wheel.file}" }
        val target = File(wheelDir, wheel.file)
        val part = File(wheelDir, wheel.file + ".part")
        val total = wheel.size
        val intact = withContext(io) {
            wheelDir.mkdirs()
            target.isFile && target.length() == total && sha256(target) == wheel.sha256
        }
        if (intact) {
            progress(total, total)
            return target
        }
        withContext(io) {
            target.delete()
            if (part.length() >= total) part.delete()
        }
        var restarted = false
        while (true) {
            val offset = withContext(io) { if (part.isFile) part.length() else 0L }
            val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap()
            val retry = withContext(io) {
                http.get(baseUrl + wheel.file, headers).use { reply ->
                    val append = when (reply.code) {
                        HttpURLConnection.HTTP_PARTIAL -> offset > 0
                        HttpURLConnection.HTTP_OK -> false
                        RANGE_NOT_SATISFIABLE -> {
                            part.delete()
                            if (restarted) throw IOException("HTTP ${reply.code} for ${wheel.file}")
                            restarted = true
                            return@use true
                        }
                        else -> throw IOException("HTTP ${reply.code} for ${wheel.file}")
                    }
                    val body = reply.body ?: throw IOException("Empty reply for ${wheel.file}")
                    copy(body, part, append, if (append) offset else 0L, total, progress)
                    false
                }
            }
            if (!retry) break
        }
        withContext(io) {
            val actual = sha256(part)
            if (part.length() != total || actual != wheel.sha256) {
                part.delete()
                throw WheelVerificationException(wheel.file, wheel.sha256, actual)
            }
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) throw IOException("Cannot move ${part.name} into place")
            }
        }
        return target
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
        progress(done, total)
        var lastReport = done
        FileOutputStream(part, append).use { out ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = body.read(buf)
                if (n < 0) break
                done += n
                // Whatever arrives beyond the expected size is wrong anyway.
                if (done > total) {
                    out.close()
                    part.delete()
                    throw WheelVerificationException(part.name.removeSuffix(".part"), "$total bytes", "more")
                }
                out.write(buf, 0, n)
                if (done - lastReport >= REPORT_EVERY || done == total) {
                    lastReport = done
                    progress(done, total)
                }
            }
            out.fd.sync()
        }
        if (lastReport != done) progress(done, total)
    }

    companion object {
        /** Where the app looks unless a debug build overrides it (see [forApp]). */
        const val DEFAULT_BASE_URL = "https://raw.githubusercontent.com/sevorp-dev/SkyPython/wheels/"
        /** In a debuggable build, `filesDir/<this>` holding a URL replaces [DEFAULT_BASE_URL]. */
        const val OVERRIDE_FILE = "wheel-index-url.txt"
        private const val INDEX = "index.json"
        private const val FETCHED_AT = "fetchedAt"
        private const val ETAG = "etag"
        private const val LAST_MODIFIED = "lastModified"
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 64 * 1024L

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        @Volatile
        private var shared: WheelIndex? = null

        /** One per process, so the Pip screen and pip share the in-memory index. */
        fun forApp(context: Context): WheelIndex {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                val app = context.applicationContext
                val installer = PythonInstaller(app)
                return WheelIndex(
                    baseUrl = baseUrlFor(app),
                    cacheDir = app.cacheDir,
                    abi = { withContext(Dispatchers.IO) { installer.runtimeAbi() } ?: Build.SUPPORTED_ABIS.first() },
                ).also { shared = it }
            }
        }

        private fun baseUrlFor(app: Context): String {
            val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (debuggable) {
                val override = try {
                    File(app.filesDir, OVERRIDE_FILE).takeIf { it.isFile }?.readText()?.trim()
                } catch (e: IOException) {
                    null
                }
                if (!override.isNullOrEmpty()) return override
            }
            return DEFAULT_BASE_URL
        }
    }
}

/** [name] is in the wheel index, but not for the device's [abi] (only for [abis]). */
class PrebuiltUnavailableException(val name: String, val abi: String, val abis: Set<String>) :
    Exception("$name is prebuilt only for ${abis.sorted().joinToString()}, not for $abi")

/** No prebuilt [name] matches [spec]; [versions] are available. */
class PrebuiltVersionException(val name: String, val spec: String, val versions: List<String>) :
    Exception("No prebuilt $name$spec (available: ${versions.joinToString()})")

/** 32-bit Android ABIs: the index starts with 64-bit builds. */
internal val ABIS_32_BIT = setOf("armeabi-v7a", "armeabi", "x86")
