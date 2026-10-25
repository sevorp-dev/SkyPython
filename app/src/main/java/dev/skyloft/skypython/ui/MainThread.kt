package dev.skyloft.skypython.ui

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import dev.skyloft.skypython.report.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Main-thread confinement for coroutines that touch Views (the Sora editor) after suspending on
 * background work (the language server, a regex on [Dispatchers.Default]).
 *
 * A composition's coroutines run on the main thread only because the host says so: Compose's
 * test rule runs them on an unconfined test dispatcher, where the code after a suspension goes
 * on in whichever thread resumed it (the language server's reader thread). Android 7 rejects
 * `View.invalidate()` from there (CalledFromWrongThreadException); later versions let it race
 * with drawing. [mainConfined] pins the dispatcher; [checkMainThread] catches a regression.
 */
fun mainConfined(context: CoroutineContext): CoroutineContext = context + Dispatchers.Main

/** [rememberCoroutineScope] whose coroutines always resume on the main thread ([mainConfined]). */
@Composable
fun rememberMainScope(): CoroutineScope = rememberCoroutineScope { mainConfined(EmptyCoroutineContext) }

object MainThread {
    /** Debug builds: a View touched off the main thread throws at once (set by the Application). */
    @Volatile var strict: Boolean = false
}

/** Asserts that [what] (a View update) runs on the main thread: throws when strict, else logs. */
fun checkMainThread(what: String) {
    if (Looper.myLooper() === Looper.getMainLooper()) return
    val message = "$what off the main thread (${Thread.currentThread().name})"
    if (MainThread.strict) throw IllegalStateException(message)
    AppLog.w("MainThread", message)
}
