package dev.skyloft.skypython.runtime

import java.io.File

/** Environment and command line for running a script with the bundled interpreter. */
object PythonEnvironment {

    /** Variables added on top of the app's own environment. */
    fun build(paths: PythonPaths): Map<String, String> = linkedMapOf(
        "PYTHONHOME" to paths.home.path,
        "LD_LIBRARY_PATH" to paths.nativeLibDir.path,
        "PYTHONUNBUFFERED" to "1",
        "PYTHONIOENCODING" to "utf-8",
        "HOME" to paths.homeDir.path,
        "TMPDIR" to paths.tmpDir.path,
    )

    fun command(paths: PythonPaths, script: File): List<String> =
        listOf(paths.launcher.path, "-u", script.path)
}
