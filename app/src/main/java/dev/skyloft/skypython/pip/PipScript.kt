package dev.skyloft.skypython.pip

/**
 * Builds the tiny Python script used to run `python -m <module> <args>` through
 * [dev.skyloft.skypython.runtime.PythonProcess], which only runs script files.
 */
object PipScript {

    /** A script that runs [module] as `__main__` with `sys.argv = [module, *args]`. */
    fun source(module: String, args: List<String>): String {
        val argv = (listOf(module) + args).joinToString(", ", "[", "]") { pythonString(it) }
        return "import runpy, sys\n" +
            "sys.argv = $argv\n" +
            "runpy.run_module(${pythonString(module)}, run_name=\"__main__\", alter_sys=True)\n"
    }

    /**
     * [value] as a double-quoted Python str literal. Control characters and lone surrogates
     * are escaped; other characters stay as they are (the script is written as UTF-8).
     */
    fun pythonString(value: String): String = buildString {
        append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == '\u007f' -> append("\\x%02x".format(c.code))
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    append(c).append(value[i + 1])
                    i++
                }
                c.isSurrogate() -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            i++
        }
        append('"')
    }
}
