package dev.skyloft.skypython.runtime

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.atomic.AtomicLong

/**
 * Copies the debugger driver (assets/debugger/skydbg.py) to `filesDir/debugger` when it is
 * missing or differs from the bundled one. Kept apart from the user's files and pip site.
 */
class DebuggerInstaller(context: Context) {

    private val context: Context = context.applicationContext

    /** The driver script, ready to run with the bundled Python ([RunCommand.Debug]). */
    suspend fun ensureInstalled(): Result<File> = withContext(Dispatchers.IO) {
        try {
            Result.success(lock.withLock {
                DebuggerBundle.ensureInstalled(File(context.filesDir, DIR)) { context.assets.open(ASSET) }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** A fresh socket path for one debug session, in the app's private cache directory. */
    fun newChannel(): File = DebuggerBundle.channelFile(context.cacheDir)

    private companion object {
        const val DIR = "debugger"
        const val ASSET = "debugger/skydbg.py"
        val lock = Mutex()
    }
}

/** Android-independent part of [DebuggerInstaller], unit-tested on the JVM. */
internal object DebuggerBundle {
    const val SCRIPT = "skydbg.py"
    private val counter = AtomicLong()

    fun ensureInstalled(dir: File, open: () -> InputStream): File {
        val bundled = open().use { it.readBytes() }
        val script = File(dir, SCRIPT)
        if (script.isFile && script.length() == bundled.size.toLong() && script.readBytes().contentEquals(bundled)) {
            return script
        }
        dir.mkdirs()
        val part = File(dir, "$SCRIPT.part")
        part.writeBytes(bundled)
        if (!part.renameTo(script)) {
            script.delete()
            if (!part.renameTo(script)) throw IOException("Cannot install $script")
        }
        return script
    }

    /** Short (a Unix socket path is limited to 107 bytes) and unique within this process. */
    fun channelFile(dir: File): File =
        File(dir, "dbg-${System.currentTimeMillis().toString(36)}-${counter.incrementAndGet()}.sock")
}

/**
 * The IDE end of a debug session: line-delimited JSON over the Unix socket on which the driver
 * (`skydbg.py`) listens. See [connect].
 */
class DebugSocket private constructor(private val socket: LocalSocket) : LineTransport {

    private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
    private val writer: Writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
    private val writeLock = Any()
    @Volatile private var closed = false

    override fun writeLine(line: String) {
        synchronized(writeLock) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    override fun readLine(): String? = reader.readLine()

    override val isAlive: Boolean get() = !closed

    override fun close() {
        if (closed) return
        closed = true
        // shutdownInput wakes a reader blocked in readLine().
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    companion object {
        private const val RETRY_MS = 50L

        /**
         * Connects to the driver listening on [channel], retrying while the driver starts up
         * (Python may still be unpacking on the first run) for as long as [keepTrying] says so,
         * at most [timeoutMs]. Blocking; throws [IOException] when it gives up.
         */
        @Throws(IOException::class)
        fun connect(channel: File, timeoutMs: Long, keepTrying: () -> Boolean): DebugSocket {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var last: IOException? = null
            while (true) {
                if (channel.exists()) {
                    val socket = LocalSocket()
                    try {
                        socket.connect(LocalSocketAddress(channel.path, LocalSocketAddress.Namespace.FILESYSTEM))
                        return DebugSocket(socket)
                    } catch (e: IOException) {
                        last = e
                        runCatching { socket.close() }
                    }
                }
                if (!keepTrying() || System.nanoTime() > deadline) {
                    throw IOException("The debugger did not start", last)
                }
                try {
                    Thread.sleep(RETRY_MS)
                } catch (e: InterruptedException) {
                    throw IOException("Interrupted", e)
                }
            }
        }
    }
}
