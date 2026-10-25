package dev.skyloft.skypython

import android.app.Application
import dev.skyloft.skypython.report.Reports

/** Runs in every process of the app; only installs the crash reporter (keep it light). */
class SkyPythonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Reports.install(this)
        Reports.cleanUpOnStart(this)
    }
}
