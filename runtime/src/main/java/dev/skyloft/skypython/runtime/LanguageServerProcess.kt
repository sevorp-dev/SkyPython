package dev.skyloft.skypython.runtime

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer

/** A line-oriented, bidirectional channel to a language server. */
interface LineTransport {
    /** Writes one line (a newline is appended). Throws [IOException] once the peer is gone. */
    @Throws(IOException::class)
    fun writeLine(line: String)

    /** Blocks for the next line; null at end of stream. */
    @Throws(IOException::class)
    fun readLine(): String?

    val isAlive: Boolean

    /** Closes the channel and stops the peer. Safe to call more than once. */
    fun close()
}

/**
 * The language server (`skylang_server.py`) running in the bundled interpreter, talking
 * line-delimited JSON over stdin / stdout. stderr is drained into [stderrTail] (the last few KB),
 * so a chatty server can never block on a full pipe.
 */
class LanguageServerProcess private constructor(private val process: Process) : LineTransport {

    private val writer: Writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
    private val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    private val writeLock = Any()
    private val tail = StringBuilder()

    /** The end of what the server printed to stderr, for logs when it dies. */
    val stderrTail: String get() = synchronized(tail) { tail.toString() }

    init {
        Thread({
            try {
                InputStreamReader(process.errorStream, Charsets.UTF_8).use { err ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = err.read(buf)
                        if (n < 0) break
                        synchronized(tail) {
                            tail.append(buf, 0, n)
                            if (tail.length > STDERR_TAIL) tail.delete(0, tail.length - STDERR_TAIL)
                        }
                    }
                }
            } catch (e: IOException) {
                // closed
            }
        }, "langserver-stderr").apply { isDaemon = true }.start()
    }

    override fun writeLine(line: String) {
        synchronized(writeLock) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    override fun readLine(): String? = reader.readLine()

    override val isAlive: Boolean get() = ProcessCompat.isAlive(process)

    override fun close() {
        synchronized(writeLock) { runCatching { writer.close() } } // EOF: the server exits by itself
        if (!ProcessCompat.waitFor(process, 500)) ProcessCompat.destroyForcibly(process)
        runCatching { reader.close() }
    }

    companion object {
        private const val STDERR_TAIL = 4096

        /** Starts [script] with the interpreter at [paths]. Throws [IOException] if it can't start. */
        @Throws(IOException::class)
        fun start(paths: PythonPaths, script: File): LanguageServerProcess {
            val builder = ProcessBuilder(PythonEnvironment.languageServerCommand(paths, script))
                .directory(paths.homeDir.apply { mkdirs() })
            builder.environment().putAll(PythonEnvironment.build(paths))
            return LanguageServerProcess(builder.start())
        }
    }
}
