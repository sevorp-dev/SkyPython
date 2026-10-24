package dev.skyloft.skypython.runtime

import java.io.File
import java.io.IOException

/**
 * Executable `python`, `python3` and `pip` scripts for the terminal's PATH. They exec the
 * launcher by absolute path, which changes on app updates, so [install] rewrites stale ones.
 */
object ShellShims {

    fun scripts(launcher: File): Map<String, String> {
        val exec = "#!/system/bin/sh\nexec ${shellQuote(launcher.path)}"
        return linkedMapOf(
            "python" to "$exec \"\$@\"\n",
            "python3" to "$exec \"\$@\"\n",
            "pip" to "$exec -m pip \"\$@\"\n",
        )
    }

    /** Writes the shims into [binDir] (created if missing) and marks them executable. */
    fun install(binDir: File, launcher: File) {
        if (!binDir.isDirectory && !binDir.mkdirs()) throw IOException("Cannot create $binDir")
        for ((name, content) in scripts(launcher)) {
            val file = File(binDir, name)
            if (!file.isFile || file.readText() != content) file.writeText(content)
            if (!file.canExecute() && !file.setExecutable(true, false)) {
                throw IOException("Cannot make $file executable")
            }
        }
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
