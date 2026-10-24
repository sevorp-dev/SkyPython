package dev.skyloft.skypython.runtime

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs the bundled stdlib into `filesDir/python` (PYTHONHOME) on first use or when the
 * bundled version changes. The native side (libpython, launcher) is already extracted by the
 * package manager into `nativeLibraryDir`.
 */
class PythonInstaller(context: Context) {

    private val context: Context = context.applicationContext

    suspend fun ensureInstalled(): Result<PythonPaths> = withContext(Dispatchers.IO) {
        try {
            Result.success(lock.withLock { install() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun install(): PythonPaths {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val launcher = File(nativeLibDir, LAUNCHER)
        check(launcher.isFile) {
            "Python launcher not found at $launcher (native libs must be extracted: useLegacyPackaging)"
        }
        val libPython = File(nativeLibDir, "libpython${BuildConfig.PYTHON_SHORT_VERSION}.so")
        val abi = InstallSupport.chooseAbi(
            InstallSupport.elfAbi(libPython),
            Build.SUPPORTED_ABIS,
            InstallSupport.SHIPPED_ABIS,
        ) ?: error("No bundled Python for this device (ABIs: ${Build.SUPPORTED_ABIS.joinToString()})")

        val home = File(context.filesDir, "python")
        // Version plus ABI: a restored backup from a device with another ABI must reinstall.
        val markerContent = "${BuildConfig.PYTHON_VERSION} $abi"
        if (!InstallSupport.isInstalled(home, MARKER, markerContent)) {
            context.assets.open("python/stdlib-$abi.zip").use { zip ->
                InstallSupport.install(zip, home, MARKER, markerContent)
            }
        }

        val homeDir = File(context.filesDir, "home").apply { mkdirs() }
        val tmpDir = context.cacheDir.apply { mkdirs() }
        return PythonPaths(
            home = home,
            nativeLibDir = nativeLibDir,
            launcher = launcher,
            homeDir = homeDir,
            tmpDir = tmpDir,
        )
    }

    private companion object {
        const val LAUNCHER = "libpylauncher.so"
        const val MARKER = ".installed"
        val lock = Mutex()
    }
}
