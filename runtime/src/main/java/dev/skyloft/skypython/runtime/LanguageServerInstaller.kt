package dev.skyloft.skypython.runtime

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Unpacks the editor's language server (assets/langserver/langserver.zip: jedi, parso,
 * pyflakes and `skylang_server.py`) to `filesDir/langserver` on first use or when the bundle
 * changes. Kept apart from the user's pip site, so `pip uninstall` can't break it and scripts
 * can't import it.
 */
class LanguageServerInstaller(context: Context) {

    private val context: Context = context.applicationContext

    /** The server script, ready to run with the bundled Python. */
    suspend fun ensureInstalled(): Result<File> = withContext(Dispatchers.IO) {
        try {
            Result.success(lock.withLock {
                LanguageServerBundle.ensureInstalled(
                    target = File(context.filesDir, DIR),
                    bundleVersion = context.assets.open(VERSION_ASSET).use { it.readBytes().decodeToString().trim() },
                    openZip = { context.assets.open(ZIP_ASSET) },
                )
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private companion object {
        const val DIR = "langserver"
        const val ZIP_ASSET = "langserver/langserver.zip"
        const val VERSION_ASSET = "langserver/langserver.version"
        val lock = Mutex()
    }
}

/** Android-independent part of [LanguageServerInstaller], unit-tested on the JVM. */
internal object LanguageServerBundle {
    const val MARKER = ".installed"
    const val SCRIPT = "skylang_server.py"

    /**
     * Unpacks [openZip] into [target] unless [target] already holds [bundleVersion] (the
     * bundle's SHA-256, written by the build). Atomic: [target] is either the old complete
     * install or the new one ([InstallSupport.install]).
     */
    fun ensureInstalled(target: File, bundleVersion: String, openZip: () -> InputStream): File {
        require(bundleVersion.isNotBlank()) { "empty language server bundle version" }
        val script = File(target, SCRIPT)
        if (!InstallSupport.isInstalled(target, MARKER, bundleVersion) || !script.isFile) {
            openZip().use { InstallSupport.install(it, target, MARKER, bundleVersion) }
        }
        check(script.isFile) { "$SCRIPT missing from the language server bundle" }
        return script
    }
}
