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
    suspend fun uninstall(name: String): Result<Unit>
    /** User-installed packages, sorted by name, without pip itself. */
    suspend fun listInstalled(): Result<List<PipPackage>>
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
        lock.withLock {
            val p = paths()
            bootstrap(p) { emit(it) }
            val args = listOf("install", "--user", "--no-input", "--progress-bar", "off", valid)
            run(p, "pip", args) { emit(it) }.orThrow("pip", includeOutput = false)
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
