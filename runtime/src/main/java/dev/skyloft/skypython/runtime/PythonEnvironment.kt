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
        // pip --user installs here, and every script/REPL sees those packages.
        "PYTHONUSERBASE" to paths.userBase.path,
        // No network round-trip (and nag) on every pip call.
        "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
    )

    fun command(paths: PythonPaths, script: File): List<String> =
        listOf(paths.launcher.path, "-u", script.path)

    // --- Interpreter and terminal (area B) -------------------------------------------------

    /** Command line for [command]. */
    fun command(paths: PythonPaths, command: RunCommand): List<String> = when (command) {
        is RunCommand.Script -> command(paths, command.file)
        is RunCommand.Interpreter -> listOf(paths.launcher.path, "-i", "-q")
        is RunCommand.Shell -> listOf(SHELL, "-i")
    }

    /** Variables added on top of the app's own environment for [command]. */
    fun environment(paths: PythonPaths, command: RunCommand): Map<String, String> =
        if (command is RunCommand.Shell) shellEnvironment(paths) else build(paths)

    /** [build] plus a PATH that finds the `python` / `pip` shims in [PythonPaths.binDir]. */
    fun shellEnvironment(paths: PythonPaths): Map<String, String> =
        build(paths) + ("PATH" to "${paths.binDir.path}:/system/bin")

    private const val SHELL = "/system/bin/sh"
}
