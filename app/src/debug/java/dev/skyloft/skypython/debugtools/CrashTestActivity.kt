package dev.skyloft.skypython.debugtools

import android.app.Activity
import android.os.Bundle
import java.io.IOException

/**
 * Debug builds only, for the crash-report device test: crashes on purpose, on the main thread,
 * in its own process (`:crashtest`). The app's crash handler saves the report there, then the
 * process dies the normal way.
 */
class CrashTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        throw IllegalStateException(MESSAGE, IOException(CAUSE))
    }

    companion object {
        const val MESSAGE = "Test crash from CrashTestActivity"
        const val CAUSE = "Test cause"
    }
}
