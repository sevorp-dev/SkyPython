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
}
