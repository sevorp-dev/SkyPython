package dev.skyloft.skypython.update

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.skyloft.skypython.BuildConfig
import java.io.File

/** What the updater talks to; device tests swap in fakes before the activity starts. */
object UpdateEnvironment {
    @VisibleForTesting
    var http: UpdateHttp = UrlUpdateHttp

    @VisibleForTesting
    var installer: ((Context) -> ApkInstaller)? = null

    @VisibleForTesting
    var versionOverride: String? = null

    /**
     * The version the updater compares with. Debug builds can pretend to be older to try the
     * real flow against GitHub: `adb shell run-as dev.skyloft.skypython sh -c 'echo 0.0.3 > files/update-version-override'`.
     */
    fun currentVersion(context: Context): String = versionOverride
        ?: debugOverride(context)
        ?: BuildConfig.VERSION_NAME

    private fun debugOverride(context: Context): String? {
        if (!BuildConfig.DEBUG) return null
        val file = File(context.filesDir, "update-version-override")
        return runCatching { file.readText().trim() }.getOrNull()?.takeIf { SemVer.parse(it) != null }
    }
}

/** The app's updater, one per activity (survives rotation; a download keeps going). */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    val controller = UpdateController(
        http = UpdateEnvironment.http,
        prefs = DataStoreUpdatePrefs(app),
        installer = UpdateEnvironment.installer?.invoke(app) ?: SystemApkInstaller(app),
        currentVersion = UpdateEnvironment.currentVersion(app),
        variant = BuildConfig.SKY_APK_VARIANT,
        downloadDir = File(app.cacheDir, "updates"),
        scope = viewModelScope,
    )

    init {
        controller.start()
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { UpdateViewModel(app) }
        }
    }
}
