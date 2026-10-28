package dev.skyloft.skypython.pip

/** `ModuleNotFoundError: No module named 'x.y'` (the quote may be ' or "). */
private val MISSING_MODULE = Regex("""ModuleNotFoundError: No module named ['"]([^'"\r\n]+)['"]""")
private val MODULE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")

/**
 * The top-level module a traceback says is missing (`x.y` → `x`), from its last
 * `ModuleNotFoundError`, or null. Relative imports and private modules (`_tkinter`: part of
 * the interpreter, not something pip can install) give null.
 */
fun missingModuleFrom(traceback: String): String? {
    val module = MISSING_MODULE.findAll(traceback).lastOrNull()?.groupValues?.get(1) ?: return null
    val top = module.substringBefore('.')
    return top.takeIf { MODULE_NAME.matches(it) }
}

/**
 * Import names whose PyPI distribution is called something else. Unknown names map to
 * themselves (the common case: `import requests` ↔ `pip install requests`).
 */
val MODULE_TO_PIP: Map<String, String> = mapOf(
    "cv2" to "opencv-python",
    "PIL" to "pillow",
    "yaml" to "pyyaml",
    "bs4" to "beautifulsoup4",
    "sklearn" to "scikit-learn",
    "skimage" to "scikit-image",
    "dateutil" to "python-dateutil",
    "serial" to "pyserial",
    "Crypto" to "pycryptodome",
    "Cryptodome" to "pycryptodomex",
    "attr" to "attrs",
    "dotenv" to "python-dotenv",
    "jwt" to "PyJWT",
    "OpenSSL" to "pyOpenSSL",
    "telegram" to "python-telegram-bot",
    "discord" to "discord.py",
    "docx" to "python-docx",
    "pptx" to "python-pptx",
    "fitz" to "PyMuPDF",
    "magic" to "python-magic",
    "usb" to "pyusb",
    "win32api" to "pywin32",
    "win32con" to "pywin32",
    "Bio" to "biopython",
    "MySQLdb" to "mysqlclient",
    "psycopg2" to "psycopg2-binary",
    "gi" to "PyGObject",
    "wx" to "wxPython",
    "kivy" to "Kivy",
    "nacl" to "PyNaCl",
    "git" to "GitPython",
    "zmq" to "pyzmq",
    "markdown" to "Markdown",
    "slugify" to "python-slugify",
    "multipart" to "python-multipart",
    "socketio" to "python-socketio",
    "engineio" to "python-engineio",
    "jose" to "python-jose",
    "Levenshtein" to "python-Levenshtein",
    "speech_recognition" to "SpeechRecognition",
    "dns" to "dnspython",
    "ldap" to "python-ldap",
    "googleapiclient" to "google-api-python-client",
    "pkg_resources" to "setuptools",
    // matplotlib ships mpl_toolkits (mplot3d, axes_grid1) and pylab.
    "mpl_toolkits" to "matplotlib",
    "pylab" to "matplotlib",
)

/** The `pip install` name for an import name (see [MODULE_TO_PIP]); `x.y` uses `x`. */
fun pipNameForModule(module: String): String {
    val top = module.trim().substringBefore('.')
    return MODULE_TO_PIP[top] ?: top
}

/** A module a run could not import, and the pip package that provides it. */
data class MissingPackage(val module: String, val pipName: String) {
    companion object {
        /**
         * Import names that are namespaces shared by many distributions (`google.protobuf`,
         * `google.cloud.storage`…): the missing package can't be told from the top-level name.
         */
        private val NAMESPACES = setOf("google")

        /** How much of the end of the output is searched: the traceback is the last thing printed. */
        private const val TAIL_CHARS = 20_000

        /**
         * What to offer after a run that ended with [exitCode] (null: still running): the package
         * for the last `ModuleNotFoundError` in [output], when the run failed and pip can provide it.
         */
        fun afterRun(exitCode: Int?, output: String): MissingPackage? {
            if (exitCode == null || exitCode == 0) return null
            val module = missingModuleFrom(output.takeLast(TAIL_CHARS)) ?: return null
            if (module in NAMESPACES) return null
            val pip = pipNameForModule(module)
            return if (PackageSpec.isName(pip)) MissingPackage(module, pip) else null
        }
    }
}
