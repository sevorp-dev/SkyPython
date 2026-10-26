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

    /**
     * The ABI of the interpreter (what native wheels must match), without installing anything;
     * null if this APK has no Python for the device. [PythonPaths.abi] is the same.
     */
    fun runtimeAbi(): String? {
        val libPython = File(context.applicationInfo.nativeLibraryDir, "libpython${BuildConfig.PYTHON_SHORT_VERSION}.so")
        return InstallSupport.chooseAbi(
            InstallSupport.elfAbi(libPython),
            Build.SUPPORTED_ABIS,
            InstallSupport.parseAbis(BuildConfig.PYTHON_ABIS),
        )
    }

    private fun install(): PythonPaths {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val launcher = File(nativeLibDir, LAUNCHER)
        check(launcher.isFile) {
            "Python launcher not found at $launcher (native libs must be extracted: useLegacyPackaging)"
        }
        val abi = runtimeAbi()
            ?: error("No bundled Python for this device (ABIs: ${Build.SUPPORTED_ABIS.joinToString()})")

        val home = File(context.filesDir, "python")
        // Version plus ABI: a restored backup from a device with another ABI must reinstall.
        val markerContent = "${BuildConfig.PYTHON_VERSION} $abi"
        if (!InstallSupport.isInstalled(home, MARKER, markerContent)) {
            InstallSupport.requireFreeSpace(context.filesDir, InstallSupport.REQUIRED_FREE_BYTES)
            context.assets.open("python/stdlib-$abi.zip").use { zip ->
                InstallSupport.install(zip, home, MARKER, markerContent)
            }
        }

        val homeDir = File(context.filesDir, "home").apply { mkdirs() }
        val tmpDir = context.cacheDir.apply { mkdirs() }
        val paths = PythonPaths(
            home = home,
            nativeLibDir = nativeLibDir,
            launcher = launcher,
            homeDir = homeDir,
            tmpDir = tmpDir,
            abi = abi,
        )
        // plt.show() in the app (PYTHONPATH and MPLBACKEND point here, see PythonEnvironment).
        PlotBackend.ensureInstalled(paths.plotBackendDir) { context.assets.open(PlotBackend.ASSET) }
        PlotBackend.deleteOld(paths.plotDir)
        return paths
    }

    private companion object {
        const val LAUNCHER = "libpylauncher.so"
        const val MARKER = ".installed"
        val lock = Mutex()
    }
}
