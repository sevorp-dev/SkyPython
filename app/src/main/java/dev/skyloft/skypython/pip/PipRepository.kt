package dev.skyloft.skypython.pip

import android.content.Context
import dev.skyloft.skypython.R
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.runtime.ConsoleEvent
import dev.skyloft.skypython.runtime.PythonInstaller
import dev.skyloft.skypython.runtime.PythonPaths
import dev.skyloft.skypython.runtime.userBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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

/** pip (or ensurepip) failed. [exitCode] is null if Python could not be started. */
class PipFailedException(val exitCode: Int?, message: String) : Exception(message)

/**
 * pip in the user site (`PYTHONUSERBASE`). The first operation bootstraps pip from the
 * bundled wheel with `ensurepip --user --default-pip` (offline); a marker in the user base
 * remembers that. One pip process at a time.
 */
class PipRepository(
    private val install: suspend () -> Result<PythonPaths>,
    private val runnerFor: (PythonPaths) -> PipRunner,
    /** First line of the log while pip is bootstrapped. */
    private val setupMessage: () -> String = { "Setting up pip (first run)…" },
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
        runInstall(listOf(valid))
    }

    override fun upgrade(name: String): Flow<String> = flow {
        require(PackageSpec.isName(name)) { "Not a package name: $name" }
        runInstall(listOf("--upgrade", name))
    }

    override fun installRequirements(file: File): Flow<String> = flow {
        require(file.isAbsolute) { "Not an absolute path: $file" }
        require(withContext(Dispatchers.IO) { file.isFile }) { "No such file: $file" }
        runInstall(listOf("-r", file.absolutePath))
    }

    /** `pip install --user …` + [args], streaming the output into the flow. */
    private suspend fun FlowCollector<String>.runInstall(args: List<String>) {
        lock.withLock {
            val p = paths()
            bootstrap(p) { emit(it) }
            run(p, "pip", INSTALL + args) { emit(it) }.orThrow("pip", includeOutput = false)
        }
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
        run(p, "ensurepip", listOf("--user", "--default-pip"), log).orThrow("ensurepip")
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
        failure?.let { throw PipFailedException(null, it) }
        val code = exitCode
        if (code == 0) return this
        val head = if (code == null) "$what did not finish" else "$what exited with code $code"
        val tail = output.trim().lines().takeLast(OUTPUT_LINES_IN_ERROR).joinToString("\n")
        throw PipFailedException(code, if (includeOutput && tail.isNotEmpty()) "$head\n$tail" else head)
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

        fun create(context: Context): PipRepository {
            val app = context.applicationContext
            val installer = PythonInstaller(app)
            return PipRepository(
                install = installer::ensureInstalled,
                runnerFor = { PythonPipRunner(it, File(app.cacheDir, "pip")) },
                setupMessage = { AppLocale.string(app, R.string.pip_setting_up) },
            )
        }
    }
}
