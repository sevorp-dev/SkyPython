package dev.skyloft.skypython.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * SkyPython's matplotlib backend (assets/plot/skypython_plot.py): `plt.show()` saves the open
 * figures as PNGs under [PythonPaths.plotDir] and sends their paths to the terminal as
 * `ESC ] 7777 ; plot ; <path>[;<path>…] BEL` (paths percent-encoded), which the app shows.
 *
 * [PythonInstaller] copies it to [PythonPaths.plotBackendDir]; [PythonEnvironment.build] puts that
 * folder on PYTHONPATH and selects it with MPLBACKEND (unless the user set their own).
 */
object PlotBackend {
    const val MODULE = "skypython_plot"
    const val ASSET = "plot/$MODULE.py"
    const val MPLBACKEND = "module://$MODULE"

    /** Plots of earlier runs older than this are deleted when Python is next prepared. */
    const val KEEP_MS = 24 * 60 * 60_000L

    /** Copies the backend to [dir] when it is missing or differs from the bundled one. */
    fun ensureInstalled(dir: File, open: () -> InputStream): File =
        InstallSupport.copyIfChanged(dir, "$MODULE.py", open)

    /** Deletes run folders in [plotDir] last written before [now] - [KEEP_MS]. Never throws. */
    fun deleteOld(plotDir: File, now: Long = System.currentTimeMillis()) {
        val cutoff = now - KEEP_MS
        plotDir.listFiles()?.forEach { run ->
            if (newest(run) < cutoff) runCatching { run.deleteRecursively() }
        }
    }

    private fun newest(file: File): Long =
        if (file.isDirectory) (file.listFiles()?.maxOfOrNull { newest(it) } ?: 0L).coerceAtLeast(file.lastModified())
        else file.lastModified()
}

/** Where [PlotBackend]'s module lives (`filesDir/plot`), on every interpreter's PYTHONPATH. */
val PythonPaths.plotBackendDir: File get() = File(home.parentFile ?: home.absoluteFile.parentFile, "plot")

/** Where `plt.show()` saves figures (`cacheDir/plots/<run>/<n>/figure-1.png`). */
val PythonPaths.plotDir: File get() = File(tmpDir, "plots")

internal fun InstallSupport.copyIfChanged(dir: File, name: String, open: () -> InputStream): File {
    val bundled = open().use { it.readBytes() }
    val target = File(dir, name)
    if (target.isFile && target.length() == bundled.size.toLong() && target.readBytes().contentEquals(bundled)) {
        return target
    }
    dir.mkdirs()
    val part = File(dir, "$name.part")
    part.writeBytes(bundled)
    if (!part.renameTo(target)) {
        target.delete()
        if (!part.renameTo(target)) throw IOException("Cannot install $target")
    }
    return target
}
