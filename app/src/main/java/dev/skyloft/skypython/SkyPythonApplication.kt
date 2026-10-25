package dev.skyloft.skypython

import android.app.Application
import android.content.pm.ApplicationInfo
import dev.skyloft.skypython.report.Reports
import dev.skyloft.skypython.ui.MainThread

/** Runs in every process of the app; only installs the crash reporter (keep it light). */
class SkyPythonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug builds (and so the device tests) fail fast on a View touched off the main thread.
        MainThread.strict = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Reports.install(this)
        Reports.cleanUpOnStart(this)
    }
}
