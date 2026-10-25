package dev.skyloft.skypython.lang

import android.content.Context
import dev.skyloft.skypython.report.AppLog
import dev.skyloft.skypython.report.ErrorReporter
import dev.skyloft.skypython.runtime.LanguageServerInstaller
import dev.skyloft.skypython.runtime.LanguageServerProcess
import dev.skyloft.skypython.runtime.LineTransport
import dev.skyloft.skypython.runtime.PythonInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The editor's language server for the whole app session: started lazily (first [warmUp] or
 * request), restarted when it dies, and abandoned for the session after [Config.maxEarlyDeaths]
 * crashes in a row, so a broken install can't burn the battery in a restart loop.
 *
 * [complete] never waits for a server that is still starting (typing must not stall: the
 * editor shows plain word completions meanwhile); [diagnose] does, since it runs in the
 * background anyway.
 */
class LanguageService(
    private val scope: CoroutineScope,
    private val start: suspend () -> LineTransport,
    private val config: Config = Config(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Config(
        val completeTimeoutMs: Long = 4_000,
        val diagnoseTimeoutMs: Long = 15_000,
        /** Go to definition, usages, outline: the user is waiting, but a big library takes a while. */
        val navigateTimeoutMs: Long = 15_000,
        /** First start unpacks the bundle and compiles jedi's bytecode: slow on old phones. */
        val readyTimeoutMs: Long = 90_000,
        val maxEarlyDeaths: Int = 3,
        /** A server that dies sooner than this after starting counts towards [maxEarlyDeaths]. */
        val earlyDeathMs: Long = 60_000,
        /**
         * This many timeouts in a row with the server silent for [stuckMs]: it is stuck in some
         * request; restart it. Merely slow (a big file on an old phone) is not stuck: a restart
         * would only add a cold start.
         */
        val maxConsecutiveTimeouts: Int = 2,
        val stuckMs: Long = 30_000,
    )

    private val lock = Any()
    private var client: LanguageClient? = null
    private var startedAt = 0L
    private var starting: Deferred<LanguageClient?>? = null
    private var earlyDeaths = 0

    @Volatile var gaveUp = false
        private set

    val isReady: Boolean get() = synchronized(lock) { client }?.isReady == true

    /** Starts the server in the background if it isn't running. Returns at once. */
    fun warmUp() {
        startAsync()
    }

    /** Completions, or null when no server is ready yet (one is started) or it failed. */
    suspend fun complete(source: String, line: Int, column: Int, path: String?): List<Completion>? {
        val c = readyClient() ?: run { startAsync(); return null }
        return c.complete(source, line, column, path, config.completeTimeoutMs).also { check(c) }
    }

    /** Diagnostics, waiting for the server to start if needed; null if unavailable. */
    suspend fun diagnose(source: String, path: String?): List<Diagnostic>? {
        val c = readyClient() ?: startAsync()?.await() ?: return null
        return c.diagnose(source, path, config.diagnoseTimeoutMs).also { check(c) }
    }

    /** Where the name at the position is defined, waiting for the server if needed; null if unavailable. */
    suspend fun definition(source: String, line: Int, column: Int, path: String?): List<Definition>? {
        val c = readyClient() ?: startAsync()?.await() ?: return null
        return c.definition(source, line, column, path, config.navigateTimeoutMs).also { check(c) }
    }

    /** Usages of the name at the position in this file, waiting for the server; null if unavailable. */
    suspend fun references(source: String, line: Int, column: Int, path: String?): List<Usage>? {
        val c = readyClient() ?: startAsync()?.await() ?: return null
        return c.references(source, line, column, path, config.navigateTimeoutMs).also { check(c) }
    }

    /** The file's classes and functions, waiting for the server; null if unavailable. */
    suspend fun outline(source: String): List<OutlineItem>? {
        val c = readyClient() ?: startAsync()?.await() ?: return null
        return c.outline(source, config.navigateTimeoutMs).also { check(c) }
    }

    /**
     * The call around the cursor, for the parameter hint. Like [complete] it never waits for a
     * starting server (null meanwhile): a hint that comes late is worse than none.
     */
    suspend fun signature(source: String, line: Int, column: Int, path: String?): Signature? {
        val c = readyClient() ?: run { startAsync(); return null }
        return c.signature(source, line, column, path, config.completeTimeoutMs).also { check(c) }
    }

    /** Stops the server (code completion was switched off). A later request starts a new one. */
    fun shutdown() {
        val c = synchronized(lock) {
            starting?.cancel()
            starting = null
            client.also { client = null }
        }
        c?.close()
    }

    private fun readyClient(): LanguageClient? {
        val c = synchronized(lock) { client } ?: return null
        if (c.isClosed) {
            died(c)
            return null
        }
        return c.takeIf { it.isReady }
    }

    /** After a call: notice a death or a stuck server. */
    private fun check(c: LanguageClient) {
        when {
            c.isClosed -> died(c)
            c.consecutiveTimeouts >= config.maxConsecutiveTimeouts && now() - c.lastMessageAt >= config.stuckMs -> {
                log("language server stuck; restarting")
                c.close()
                died(c)
            }
        }
    }

    private fun died(c: LanguageClient) {
        synchronized(lock) {
            if (client !== c) return // already handled (or shut down on purpose)
            client = null
            countDeath()
        }
    }

    /** Under [lock]. */
    private fun countDeath() {
        if (now() - startedAt < config.earlyDeathMs) earlyDeaths++ else earlyDeaths = 1
        if (earlyDeaths >= config.maxEarlyDeaths) {
            gaveUp = true
            log("language server keeps dying; completions fall back to plain words")
            ErrorReporter.report(
                ErrorReporter.Where.LANGUAGE_SERVER,
                "the language server stopped $earlyDeaths times in a row; code hints are off until restart",
            )
        }
    }

    private fun startAsync(): Deferred<LanguageClient?>? = synchronized(lock) {
        if (gaveUp) return null
        client?.let { c -> if (!c.isClosed) return starting?.takeIf { it.isActive } ?: CompletableDeferred(c) }
        starting?.takeIf { it.isActive }?.let { return it }
        // A closed client still registered: count its death before starting anew.
        client?.let { client = null; countDeath(); if (gaveUp) return null }
        scope.async { launchServer() }.also { starting = it }
    }

    private suspend fun launchServer(): LanguageClient? {
        val c = try {
            LanguageClient(start(), scope, now = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("language server failed to start: $e")
            synchronized(lock) { startedAt = now(); countDeath() }
            return null
        }
        val context = currentCoroutineContext()
        val registered = synchronized(lock) {
            // Switched off ([shutdown]) while the server was starting: the start can't be
            // interrupted, so the new process is stopped here instead of left running.
            if (!context.isActive) {
                false
            } else {
                startedAt = now()
                client = c
                true
            }
        }
        if (!registered) {
            c.close()
            throw CancellationException("language server switched off while starting")
        }
        val ready = try {
            c.awaitReady(config.readyTimeoutMs)
        } catch (e: CancellationException) {
            c.close()
            synchronized(lock) { if (client === c) client = null }
            throw e
        }
        if (!ready) {
            log("language server not ready; stopping it")
            c.close()
            died(c)
            return null
        }
        return c
    }

    companion object {
        private const val TAG = "LanguageService"

        private fun log(message: String) = AppLog.w(TAG, message)

        @Volatile private var instance: LanguageService? = null

        /** The app-wide server: bundled Python + `filesDir/langserver`. */
        fun get(context: Context): LanguageService = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                LanguageService(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    start = {
                        val paths = PythonInstaller(app).ensureInstalled().getOrThrow()
                        val script = LanguageServerInstaller(app).ensureInstalled().getOrThrow()
                        LanguageServerProcess.start(paths, script)
                    },
                ).also { instance = it }
            }
        }
    }
}
