package dev.skyloft.skypython.pip

import android.content.Context
import dev.skyloft.skypython.R
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.report.ErrorReporter
import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.userBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** What the Pip screen needs; [PipRepository] is the real one. */
interface PipManager {
    /**
     * Installs [spec] for the user, emitting pip's output (stdout and stderr) as it comes.
     * Completes normally on success; throws [PipFailedException] when pip fails and
     * [IllegalArgumentException] for a spec that [PackageSpec] rejects.
     */
    fun install(spec: String): Flow<String>
    /** `pip install --upgrade <name>`; like [install]. */
    fun upgrade(name: String): Flow<String>
    /** `pip install -r <file>` ([file] absolute and existing); like [install]. */
    fun installRequirements(file: File): Flow<String>
    suspend fun uninstall(name: String): Result<Unit>
    /** User-installed packages, sorted by name, without pip itself. */
    suspend fun listInstalled(): Result<List<PipPackage>>
    /** User packages with a newer release: normalized name → latest version (needs network). */
    suspend fun listOutdated(): Result<Map<String, String>>
    /** `pip show <name>`. */
    suspend fun show(name: String): Result<PackageDetails>
}

/**
 * pip (or ensurepip, [what]) failed: it exited with [exitCode] (null: it didn't finish), or
 * Python could not start ([startFailure]). [output] is the end of what it printed. The message
 * is English, for logs and reports; [describePipError] gives the user's text.
 */
class PipFailedException(
    val exitCode: Int?,
    val what: String = "pip",
    val output: String = "",
    val startFailure: String? = null,
) : Exception(
    startFailure ?: buildString {
        append(if (exitCode == null) "$what did not finish" else "$what exited with code $exitCode")
        if (output.isNotEmpty()) append("\n").append(output)
    },
)

/** [e] in the app's language ([text] reads a string resource), the technical detail appended. */
fun describePipError(e: Throwable, text: (Int, Array<out Any>) -> String): String = when (e) {
    is PipFailedException -> {
        val head = when {
            e.startFailure != null -> text(R.string.pip_error_start, arrayOf(e.startFailure))
            e.exitCode == null -> text(R.string.pip_error_unfinished, arrayOf(e.what))
            else -> text(R.string.pip_error_exit, arrayOf(e.what, e.exitCode))
        }
        if (e.output.isBlank()) head else head + "\n" + e.output.trim()
    }
    is PrebuiltUnavailableException ->
        if (e.abi in ABIS_32_BIT) text(R.string.pip_error_prebuilt_32bit, arrayOf(e.name))
        else text(R.string.pip_error_prebuilt_abi, arrayOf(e.name, e.abi))
    is PrebuiltVersionException ->
        text(R.string.pip_error_prebuilt_version, arrayOf(e.name, e.spec, e.versions.joinToString(", ")))
    is WheelVerificationException -> text(R.string.pip_error_wheel_checksum, arrayOf(e.file))
    else -> text(R.string.pip_error_other, arrayOf(e.message ?: e.javaClass.simpleName))
}

/**
 * pip in the user site (`PYTHONUSERBASE`). The first operation bootstraps pip from the
 * bundled wheel with `ensurepip --user --default-pip` (offline); a marker in the user base
 * remembers that. One pip process at a time.
 *
 * Packages in SkyPython's wheel index ([wheels]) are downloaded and verified first, then pip
 * installs them with `--find-links <wheel folder> --prefer-binary`, pinned to the downloaded
 * versions: the native wheel comes from the folder, pure-Python dependencies from PyPI.
 */
class PipRepository(
    private val install: suspend () -> Result<PythonPaths>,
    private val runnerFor: (PythonPaths) -> PipRunner,
    /** First line of the log while pip is bootstrapped. */
    private val setupMessage: () -> String = { "Setting up pip (first run)…" },
    /** SkyPython's prebuilt Android wheels; null: PyPI only. */
    private val wheels: PrebuiltWheels? = null,
    /** The log line before a wheel is downloaded. */
    private val downloadMessage: (IndexWheel) -> String = { w ->
        "Downloading ${w.name} ${w.version} (${formatSize(w.size)}), prebuilt for Android by SkyPython…"
    },
) : PipManager {

    private val lock = Mutex()

    /** Bootstraps pip unless already done. */
    suspend fun ensurePip(): Result<Unit> = catching {
        lock.withLock { bootstrap(paths()) {} }
    }

    /** `pip --version`, e.g. "pip 25.2 from … (python 3.14)". */
    suspend fun pipVersion(): Result<String> = catching {
        lock.withLock {
            val p = paths()
            bootstrap(p) {}
            run(p, "pip", listOf("--version")) {}.orThrow("pip").stdout.trim()
        }
    }

    override fun install(spec: String): Flow<String> = flow {
        val valid = when (val check = PackageSpec.check(spec)) {
            is SpecCheck.Valid -> check.spec
            is SpecCheck.Invalid -> throw IllegalArgumentException(check.reason)
        }
        runInstall(emptyList(), valid) { emit(it) }
    }

    override fun upgrade(name: String): Flow<String> = flow {
        require(PackageSpec.isName(name)) { "Not a package name: $name" }
        runInstall(listOf("--upgrade"), name) { emit(it) }
    }

    override fun installRequirements(file: File): Flow<String> = flow {
        require(file.isAbsolute) { "Not an absolute path: $file" }
        require(withContext(Dispatchers.IO) { file.isFile }) { "No such file: $file" }
        runInstall(listOf("-r", file.absolutePath), null) { emit(it) }
    }

    /**
     * `pip install --user …` + [options] + [request] (null: none), streaming the output to [log].
     * A [request] for a package of the wheel index downloads its wheels first.
     */
    private suspend fun runInstall(options: List<String>, request: String?, log: suspend (String) -> Unit) {
        try {
            installOnce(options, request, freshIndex = false, log)
        } catch (e: WheelVerificationException) {
            // The wheel on the server may be newer than the index cached on the phone: read the
            // index again and try once more.
            installOnce(options, request, freshIndex = true, log)
        }
    }

    private suspend fun installOnce(
        options: List<String>,
        request: String?,
        freshIndex: Boolean,
        log: suspend (String) -> Unit,
    ) {
        val catalog = if (freshIndex) wheels?.refresh() else wheels?.catalog()
        val plan = request?.let { catalog?.plan(it) } ?: WheelPlan.NotInIndex
        val targets = when (plan) {
            WheelPlan.NotInIndex -> listOfNotNull(request)
            is WheelPlan.Unavailable -> throw PrebuiltUnavailableException(plan.name, plan.abi, plan.abis)
            is WheelPlan.NoVersion -> throw PrebuiltVersionException(plan.name, plan.spec, plan.versions)
            is WheelPlan.Ready -> {
                val dir = requireNotNull(wheels).wheelDir
                val pinned = plan.wheels.mapIndexed { i, w ->
                    val extras = if (i == plan.wheels.lastIndex) plan.extras else ""
                    "${w.name}$extras==${w.version}"
                }
                listOf("--find-links", dir.absolutePath, "--prefer-binary") + pinned
            }
        }
        lock.withLock {
            if (plan is WheelPlan.Ready) {
                for (w in plan.wheels) download(w, log)
            }
            val p = paths()
            bootstrap(p, log)
            run(p, "pip", INSTALL + options + targets, log).orThrow("pip", includeOutput = false)
        }
    }

    /** Downloads [wheel], logging "  10%  20% … 100%" as it goes. */
    private suspend fun download(wheel: IndexWheel, log: suspend (String) -> Unit) {
        log(downloadMessage(wheel) + "\n")
        // The download reports from its IO thread; the log (a flow) is written only from here.
        val steps = Channel<Int>(Channel.CONFLATED)
        var shown = 0
        coroutineScope {
            launch {
                requireNotNull(wheels).download(wheel) { done, total ->
                    steps.send(if (total <= 0) 100 else (done * 100 / total).toInt() / 10 * 10)
                }
                steps.close()
            }
            for (step in steps) {
                if (step > shown) {
                    log("  $step%")
                    shown = step
                }
            }
        }
        log(if (shown == 100) "\n" else "  100%\n")
    }

    override suspend fun listOutdated(): Result<Map<String, String>> = catching {
        lock.withLock {
            val p = paths()
            bootstrap(p) {}
            val out = run(p, "pip", listOf("list", "--outdated", "--user", "--format=json") + QUICK_NETWORK) {}.orThrow("pip").stdout
            val outdated = PipList.parseOutdated(out) ?: error("Unexpected output from pip list:\n${out.take(500)}")
            outdated - "pip"
        }
    }

    override suspend fun show(name: String): Result<PackageDetails> = catching {
        require(PackageSpec.isName(name)) { "Not a package name: $name" }
        lock.withLock {
            val p = paths()
            bootstrap(p) {}
            val out = run(p, "pip", listOf("show", name)) {}.orThrow("pip").stdout
            PipShow.parse(out) ?: error("Unexpected output from pip show:\n${out.take(500)}")
        }
    }

    override suspend fun uninstall(name: String): Result<Unit> = catching {
        require(PackageSpec.isName(name)) { "Not a package name: $name" }
        lock.withLock {
            val p = paths()
            bootstrap(p) {}
            run(p, "pip", listOf("uninstall", "-y", name)) {}.orThrow("pip")
        }
        Unit
    }

    override suspend fun listInstalled(): Result<List<PipPackage>> = catching {
        lock.withLock {
            val p = paths()
            bootstrap(p) {}
            val out = run(p, "pip", listOf("list", "--user", "--format=json")) {}.orThrow("pip").stdout
            val packages = PipList.parse(out) ?: error("Unexpected output from pip list:\n${out.take(500)}")
            packages.filterNot { it.name.equals("pip", ignoreCase = true) }
        }
    }

    private suspend fun paths(): PythonPaths = install().getOrThrow()

    private suspend fun bootstrap(p: PythonPaths, log: suspend (String) -> Unit) {
        val marker = File(p.userBase, MARKER)
        if (withContext(Dispatchers.IO) { marker.isFile }) return
        log(setupMessage() + "\n")
        try {
            run(p, "ensurepip", listOf("--user", "--default-pip"), log).orThrow("ensurepip")
        } catch (e: PipFailedException) {
            // ensurepip is offline and bundled: its failure is ours, not the user's network.
            ErrorReporter.report(ErrorReporter.Where.PIP, e)
            throw e
        }
        withContext(Dispatchers.IO) {
            p.userBase.mkdirs()
            marker.writeText("ensurepip --user --default-pip\n")
        }
    }

    private class Run(val exitCode: Int?, val stdout: String, val output: String, val failure: String?)

    private suspend fun run(
        p: PythonPaths,
        module: String,
        args: List<String>,
        log: suspend (String) -> Unit,
    ): Run {
        val stdout = StringBuilder()
        val output = StringBuilder()
        var exitCode: Int? = null
        var failure: String? = null
        runnerFor(p).run(module, args).collect { event ->
            when (event) {
                is ConsoleEvent.Stdout -> {
                    stdout.append(event.text)
                    output.append(event.text)
                    log(event.text)
                }
                is ConsoleEvent.Stderr -> {
                    output.append(event.text)
                    log(event.text)
                }
                is ConsoleEvent.Exited -> exitCode = event.code
                is ConsoleEvent.Failed -> failure = event.message
            }
        }
        if (module == "pip" && exitCode != 0 && "No module named pip" in output) {
            // pip was removed behind our back (e.g. from the terminal): bootstrap again next time.
            withContext(Dispatchers.IO) { File(p.userBase, MARKER).delete() }
        }
        return Run(exitCode, stdout.toString(), output.toString(), failure)
    }

    private fun Run.orThrow(what: String, includeOutput: Boolean = true): Run {
        failure?.let { throw PipFailedException(null, what = what, startFailure = it) }
        val code = exitCode
        if (code == 0) return this
        val tail = output.trim().lines().takeLast(OUTPUT_LINES_IN_ERROR).joinToString("\n")
        throw PipFailedException(code, what = what, output = if (includeOutput) tail else "")
    }

    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        /** In the user base, so wiping `.local` also forgets the bootstrap. */
        const val MARKER = ".pip-bootstrapped"
        private const val OUTPUT_LINES_IN_ERROR = 15
        /** An update check must not hold the pip lock for a minute of retries when offline. */
        private val QUICK_NETWORK = listOf("--retries", "1", "--timeout", "10")
        private val INSTALL = listOf("install", "--user", "--no-input", "--progress-bar", "off")

        /** "1.2 MB" / "340 KB". */
        fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)
            bytes >= 1_000 -> "${bytes / 1_000} KB"
            else -> "$bytes B"
        }

        /** @param wheels SkyPython's wheel index (tests point it elsewhere). */
        fun create(context: Context, wheels: PrebuiltWheels? = WheelIndex.forApp(context)): PipRepository {
            val app = context.applicationContext
            val installer = PythonInstaller(app)
            return PipRepository(
                install = installer::ensureInstalled,
                runnerFor = { PythonPipRunner(it, File(app.cacheDir, "pip")) },
                setupMessage = { AppLocale.string(app, R.string.pip_setting_up) },
                wheels = wheels,
                downloadMessage = { w ->
                    AppLocale.string(app, R.string.pip_downloading_wheel, w.name, w.version, formatSize(w.size))
                },
            )
        }
    }
}
