package dev.skyloft.skypython.pip

/** Reading pip's output. */
object PipLog {

    private val buildFailureMarkers = listOf(
        "failed building wheel",
        "could not build wheels",
        "failed to build",
        "subprocess-exited-with-error",
        "metadata-generation-failed",
        "error: command '",
        "no such file or directory: 'cc'",
        "no such file or directory: 'gcc'",
        "unknown compiler",
        "c++ 14.0 or greater is required",
    )

    /**
     * True when pip failed because something had to be compiled (no C compiler on the
     * device), so the user can be told that only pure-Python packages work.
     */
    fun isBuildFailure(log: String): Boolean {
        val lower = log.lowercase()
        return buildFailureMarkers.any { it in lower }
    }

    private const val SUCCESS = "Successfully installed "

    /**
     * The packages pip's "Successfully installed six-1.17.0 rich-14.0.0" line names, so the
     * screen can show them as installed before `pip list` has run again.
     */
    fun installed(log: String): List<PipPackage> = log.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(SUCCESS) }
        .flatMap { line -> line.removePrefix(SUCCESS).split(' ').asSequence() }
        .mapNotNull { token ->
            val dash = token.lastIndexOf('-')
            if (dash <= 0 || dash == token.length - 1) null else PipPackage(token.substring(0, dash), token.substring(dash + 1))
        }
        .toList()
}
