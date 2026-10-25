package dev.skyloft.skypython.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Installs with the PackageInstaller session API: the APK is copied into a session and committed;
 * Android shows its own "Update this app?" confirmation, then replaces the app (data kept, as the
 * key and package are the same). Where a session can't be opened, the APK is handed to the
 * system's installer screen through the FileProvider instead.
 */
class SystemApkInstaller(context: Context) : ApkInstaller {
    private val context = context.applicationContext

    override val results: Flow<InstallResult> = InstallEvents.results

    override fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override suspend fun install(file: File) = withContext(Dispatchers.IO) {
        try {
            commitSession(file)
        } catch (e: IOException) {
            viewInstall(file, e)
        } catch (e: SecurityException) {
            viewInstall(file, e)
        }
    }

    private fun commitSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Once SkyPython installed itself, Android 12+ may update it without asking again.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION)
                    .setPackage(context.packageName)
                // Mutable: the system adds the status extras. The intent is explicit.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** The old way: the system installer opens the APK by its content URI. */
    private fun viewInstall(file: File, cause: Exception) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            InstallEvents.emit(InstallResult.HandedOff)
        } catch (e: ActivityNotFoundException) {
            throw IOException(cause.message ?: e.message, cause)
        }
    }

    companion object {
        /** The "Install unknown apps" switch for SkyPython (Android 8+). */
        fun unknownSourcesIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            } else {
                @Suppress("DEPRECATION")
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
    }
}

/** Install outcomes from [UpdateInstallReceiver] to whoever is listening (the updater). */
object InstallEvents {
    private val flow = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
    val results: Flow<InstallResult> = flow

    fun emit(result: InstallResult) {
        flow.tryEmit(result)
    }
}

/** Receives the session's status: shows the system's confirmation, then reports the outcome. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    InstallEvents.emit(InstallResult.Failed(status, message))
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    InstallEvents.emit(InstallResult.Failed(PackageInstaller.STATUS_FAILURE, e.message))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.emit(InstallResult.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.emit(InstallResult.Cancelled)
            else -> InstallEvents.emit(InstallResult.Failed(status, message))
        }
    }

    companion object {
        const val ACTION = "dev.skyloft.skypython.action.UPDATE_INSTALL_STATUS"
    }
}
