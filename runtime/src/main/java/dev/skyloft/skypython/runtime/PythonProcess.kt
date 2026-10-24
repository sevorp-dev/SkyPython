package dev.skyloft.skypython.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * Runs one script at a time in a separate interpreter process.
 *
 * Output is read in chunks (not lines) so prompts without a newline appear immediately.
 */
class PythonProcess(private val paths: PythonPaths) {

    @Volatile private var process: Process? = null
    private var stdin: Writer? = null
    private val stdinLock = Any()
    private val killerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Starts [script] (cwd = its directory) when collected. Emits stdout/stderr chunks, then
     * [ConsoleEvent.Exited] last, or only [ConsoleEvent.Failed] if the process cannot start.
     * Cancelling the collector kills the process.
     */
    fun start(script: File): Flow<ConsoleEvent> = start(RunCommand.Script(script))

    /**
     * Starts [command] when collected; same events and cancellation as `start(script)`.
     * [RunCommand.Shell] first (re)writes the `python` / `pip` shims into [PythonPaths.binDir].
     */
    fun start(command: RunCommand): Flow<ConsoleEvent> = channelFlow {
        if (isRunning) {
            send(ConsoleEvent.Failed("A script is already running"))
            return@channelFlow
        }
        val builder = try {
            prepare(command)
        } catch (e: IOException) {
            send(ConsoleEvent.Failed("Cannot start: ${e.message}"))
            return@channelFlow
        }

        val p = try {
            builder.start()
        } catch (e: IOException) {
            val what = if (command is RunCommand.Shell) "the shell" else "Python"
            send(ConsoleEvent.Failed("Cannot start $what: ${e.message}"))
            return@channelFlow
        }
        // stdin first, so write() works as soon as isRunning is true.
        synchronized(stdinLock) { stdin = OutputStreamWriter(p.outputStream, Charsets.UTF_8) }
        process = p
        try {
            // Pumps live outside this flow's scope: a child process the script started can keep
            // the pipes open after the script exits, and a blocked read can't be cancelled.
            val out = killerScope.launch { pump(p.inputStream, this@channelFlow) { ConsoleEvent.Stdout(it) } }
            val err = killerScope.launch { pump(p.errorStream, this@channelFlow) { ConsoleEvent.Stderr(it) } }
            val code = runInterruptible(Dispatchers.IO) { p.waitFor() }
            val drained = withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                out.join()
                err.join()
            }
            if (drained == null) {
                runCatching { p.inputStream.close() }
                runCatching { p.errorStream.close() }
                out.cancel()
                err.cancel()
            }
            send(ConsoleEvent.Exited(code))
        } finally {
            synchronized(stdinLock) {
                runCatching { stdin?.close() }
                stdin = null
            }
            if (p.isAlive) p.destroyForcibly()
            if (process === p) process = null
        }
    }

    private fun prepare(command: RunCommand): ProcessBuilder {
        when (command) {
            is RunCommand.Script -> Unit
            is RunCommand.Interpreter -> command.cwd.mkdirs()
            is RunCommand.Shell -> {
                command.cwd.mkdirs()
                ShellShims.install(paths.binDir, paths.launcher)
            }
        }
        val builder = ProcessBuilder(PythonEnvironment.command(paths, command))
            .directory(command.workingDir)
        builder.environment().putAll(PythonEnvironment.environment(paths, command))
        return builder
    }

    /** Sends [text] to the script's stdin. Ignored when nothing is running. */
    fun write(text: String) {
        synchronized(stdinLock) {
            val w = stdin ?: return
            try {
                w.write(text)
                w.flush()
            } catch (e: IOException) {
                // The process closed stdin or exited; the flow reports the exit.
            }
        }
    }

    /** `destroy()`, then `destroyForcibly()` if still alive after 1 s. */
    fun stop() {
        val p = process ?: return
        p.destroy()
        killerScope.launch {
            if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    private fun pump(
        stream: InputStream,
        channel: SendChannel<ConsoleEvent>,
        event: (String) -> ConsoleEvent,
    ) {
        InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            val buf = CharArray(8 * 1024)
            while (true) {
                val n = try {
                    reader.read(buf)
                } catch (e: IOException) {
                    -1 // stream closed by destroy()
                }
                if (n < 0) break
                if (n > 0) {
                    // The flow may already be finished (see DRAIN_TIMEOUT_MS); then stop reading.
                    if (channel.trySendBlocking(event(String(buf, 0, n))).isFailure) break
                }
            }
        }
    }

    private companion object {
        /** How long to wait for remaining output after the script itself has exited. */
        const val DRAIN_TIMEOUT_MS = 2_000L
    }
}
