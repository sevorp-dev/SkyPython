package dev.skyloft.skypython.runtime

import java.io.File

/**
 * Locations of an installed Python runtime.
 *
 * @property home PYTHONHOME (`filesDir/python`); the stdlib lives in `home/lib/python3.14`.
 * @property nativeLibDir `applicationInfo.nativeLibraryDir`, holding libpython and its deps.
 * @property launcher the `libpylauncher.so` executable inside [nativeLibDir].
 * @property homeDir HOME for scripts (`filesDir/home`).
 * @property tmpDir TMPDIR for scripts (`cacheDir`).
 * @property binDir the terminal's `python` / `pip` shims (`filesDir/home/bin`), first on its PATH.
 * @property abi the Android ABI of the interpreter (`arm64-v8a`, `x86_64`…), when known: what
 *   native wheels must be built for.
 */
data class PythonPaths(
    val home: File,
    val nativeLibDir: File,
    val launcher: File,
    val homeDir: File,
    val tmpDir: File,
    val binDir: File = File(homeDir, "bin"),
    val abi: String? = null,
)
