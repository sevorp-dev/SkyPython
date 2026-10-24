package dev.skyloft.skypython.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
    fun start(script: File): Flow<ConsoleEvent> = channelFlow {
        if (isRunning) {
            send(ConsoleEvent.Failed("A script is already running"))
            return@channelFlow
        }
        val builder = ProcessBuilder(PythonEnvironment.command(paths, script))
            .directory(script.absoluteFile.parentFile)
        builder.environment().putAll(PythonEnvironment.build(paths))

        val p = try {
            builder.start()
        } catch (e: IOException) {
            send(ConsoleEvent.Failed("Cannot start Python: ${e.message}"))
            return@channelFlow
        }
        // stdin first, so write() works as soon as isRunning is true.
        synchronized(stdinLock) { stdin = OutputStreamWriter(p.outputStream, Charsets.UTF_8) }
        process = p
        try {
            val out = launch(Dispatchers.IO) { pump(p.inputStream, this@channelFlow) { ConsoleEvent.Stdout(it) } }
            val err = launch(Dispatchers.IO) { pump(p.errorStream, this@channelFlow) { ConsoleEvent.Stderr(it) } }
            val code = runInterruptible(Dispatchers.IO) { p.waitFor() }
            out.join()
            err.join()
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

    private suspend fun pump(
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
                if (n > 0) channel.send(event(String(buf, 0, n)))
            }
        }
    }
}
