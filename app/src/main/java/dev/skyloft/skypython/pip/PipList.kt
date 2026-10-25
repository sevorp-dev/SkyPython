package dev.skyloft.skypython.pip

data class PipPackage(val name: String, val version: String)

/** Parses `pip list --format=json` (a JSON array of `{"name", "version", ...}` objects). */
object PipList {

    /**
     * The packages sorted by name, or null if the output isn't such an array. Lines before
     * the array (stray warnings) are skipped; entries without a string name and version are
     * ignored.
     */
    fun parse(stdout: String): List<PipPackage>? {
        val items = array(stdout) ?: return null
        return items.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val name = obj["name"] as? String ?: return@mapNotNull null
            val version = obj["version"] as? String ?: return@mapNotNull null
            PipPackage(name, version)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * `pip list --outdated --format=json`: PEP 503 normalized name → `latest_version`, or null
     * if the output isn't such an array.
     */
    fun parseOutdated(stdout: String): Map<String, String>? {
        val items = array(stdout) ?: return null
        return items.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val name = obj["name"] as? String ?: return@mapNotNull null
            val latest = obj["latest_version"] as? String ?: return@mapNotNull null
            PyPiJson.normalize(name) to latest
        }.toMap()
    }

    private fun array(stdout: String): List<*>? =
        // A "[notice] ..." line also starts with '[', so try each candidate line.
        ARRAY_LINE.findAll(stdout).firstNotNullOfOrNull { match ->
            try {
                JsonReader(stdout, match.range.last).readDocument() as? List<*>
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    /** The first line that starts (after indentation) with `[`. */
    private val ARRAY_LINE = Regex("""(?m)^[ \t]*\[""")

    /** A minimal strict JSON reader; throws IllegalArgumentException on bad input. */
    private class JsonReader(private val s: String, private var i: Int) {

        fun readDocument(): Any? {
            val v = readValue()
            skipWs()
            require(i == s.length) { "trailing data at $i" }
            return v
        }

        private fun readValue(): Any? {
            skipWs()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '[' -> readArray()
                '{' -> readObject()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected '$c'")
            }
        }

        private fun readArray(): List<Any?> {
            i++ // [
            val out = mutableListOf<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += readValue()
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++ // {
            val out = linkedMapOf<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                require(peek() == '"') { "expected key" }
                val key = readString()
                skipWs()
                require(next() == ':') { "expected :" }
                out[key] = readValue()
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected , or }")
                }
            }
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> when (val e = next()) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(i + 4 <= s.length) { "bad \\u escape" }
                            val hex = s.substring(i, i + 4)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                            i += 4
                        }
                        else -> fail("bad escape \\$e")
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val m = NUMBER.matchAt(s, i) ?: fail("bad number")
            i += m.value.length
            return m.value.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal" }
            i += word.length
            return value
        }

        private fun skipWs() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        private fun peek(): Char? = s.getOrNull(i)

        private fun next(): Char {
            require(i < s.length) { "unexpected end" }
            return s[i++]
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

        private companion object {
            val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        }
    }
}
