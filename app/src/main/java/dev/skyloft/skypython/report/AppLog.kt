package dev.skyloft.skypython.report

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A fixed-size, thread-safe buffer of the last [capacity] lines. */
class RingLog(private val capacity: Int) {
    private val lines = arrayOfNulls<String>(capacity)
    private var next = 0
    private var size = 0

    @Synchronized
    fun add(line: String) {
        lines[next] = line
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = List(size) { i -> lines[(next - size + i + capacity) % capacity]!! }

    /** The last [n] lines, oldest first. */
    fun tail(n: Int): List<String> = snapshot().takeLast(n)

    @Synchronized
    fun clear() {
        lines.fill(null)
        next = 0
        size = 0
    }
}

/**
 * Logcat plus an in-memory ring of recent lines that goes into problem reports (logcat itself
 * can't be read by the app). For the app's own diagnostics only: never log the user's code or
 * files' contents here.
 */
object AppLog {
    const val CAPACITY = 500

    private val ring = RingLog(CAPACITY)
    private val time = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun d(tag: String, message: String, error: Throwable? = null) = log('D', tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = log('I', tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = log('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log('E', tag, message, error)

    /** The last [n] lines, oldest first. */
    fun tail(n: Int = ReportFormatter.LOG_LINES): List<String> = ring.tail(n)

    fun clear() = ring.clear()

    private fun log(level: Char, tag: String, message: String, error: Throwable?) {
        val suffix = if (error == null) "" else " | $error"
        ring.add("${time.get()!!.format(Date())} $level/$tag: $message$suffix")
        try {
            when (level) {
                'D' -> Log.d(tag, message, error)
                'I' -> Log.i(tag, message, error)
                'W' -> Log.w(tag, message, error)
                else -> Log.e(tag, message, error)
            }
        } catch (e: RuntimeException) {
            // JVM unit tests: android.util.Log is a throwing stub.
        }
    }
}
