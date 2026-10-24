package dev.skyloft.skypython.pip

sealed interface SpecCheck {
    /** [spec] is trimmed and safe to pass to pip as one argument. */
    data class Valid(val spec: String) : SpecCheck
    data class Invalid(val reason: String) : SpecCheck
}

/**
 * What the Install field accepts: a PyPI name with optional extras and version specifiers
 * (`six`, `name==1.2`, `name>=1,<2`, `requests[socks]`). No spaces, URLs, paths or pip
 * options, so the input can never turn into something other than a package request.
 */
object PackageSpec {

    private const val NAME = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"
    private const val VERSION = "[A-Za-z0-9][A-Za-z0-9.*+!_-]*"
    private const val CLAUSE = "(?:===|==|!=|<=|>=|~=|<|>)$VERSION"
    private val nameRegex = Regex(NAME)
    private val specRegex = Regex("$NAME(?:\\[$NAME(?:,$NAME)*\\])?(?:$CLAUSE(?:,$CLAUSE)*)?")

    fun check(input: String): SpecCheck {
        val spec = input.trim()
        return when {
            spec.isEmpty() -> SpecCheck.Invalid("Enter a package name")
            spec.any { it.isWhitespace() } -> SpecCheck.Invalid("Install one package at a time, without spaces")
            spec.startsWith("-") -> SpecCheck.Invalid("Options are not supported here")
            !specRegex.matches(spec) -> SpecCheck.Invalid("Use a package name, e.g. six or requests==2.32.3")
            else -> SpecCheck.Valid(spec)
        }
    }

    /** A bare distribution name (what `pip uninstall` gets). */
    fun isName(value: String): Boolean = nameRegex.matches(value)
}
