package dev.skyloft.skypython.runtime

import java.io.File

/**
 * PYTHONUSERBASE (`homeDir/.local`): `pip install --user` puts packages in its
 * `lib/python3.X/site-packages`, which every interpreter we start has on `sys.path`.
 */
val PythonPaths.userBase: File get() = File(homeDir, ".local")
