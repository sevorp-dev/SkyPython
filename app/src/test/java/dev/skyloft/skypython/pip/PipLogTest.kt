package dev.skyloft.skypython.pip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipLogTest {

    @Test
    fun recognisesBuildFailures() {
        for (log in listOf(
            "error: subprocess-exited-with-error\n× Building wheel for numpy (pyproject.toml) did not run successfully.",
            "ERROR: Failed building wheel for psutil",
            "ERROR: Could not build wheels for cffi, which is required to install pyproject.toml-based projects",
            "ERROR: Failed to build installable wheels for some pyproject.toml based projects (lxml)",
            "error: command 'gcc' failed: No such file or directory",
            "FileNotFoundError: [Errno 2] No such file or directory: 'cc'",
            "error: Microsoft Visual C++ 14.0 or greater is required.",
            "metadata-generation-failed",
            "Unknown compiler(s): [['cc'], ['gcc'], ['clang']]",
        )) {
            assertTrue(log, PipLog.isBuildFailure(log))
        }
    }

    @Test
    fun otherFailuresAreNotBuildFailures() {
        for (log in listOf(
            "ERROR: Could not find a version that satisfies the requirement nosuchpkg",
            "ERROR: No matching distribution found for nosuchpkg",
            "WARNING: Retrying ... NewConnectionError",
            "Successfully installed six-1.16.0",
        )) {
            assertFalse(log, PipLog.isBuildFailure(log))
        }
    }
}
