package dev.skyloft.skypython.pip

sealed interface SpecCheck {
    /** [spec] is trimmed and safe to pass to pip as one argument. */
    data class Valid(val spec: String) : SpecCheck
    data class Invalid(val problem: SpecProblem) : SpecCheck {
        /** English reason (logs, tests); the UI shows a localized one. */
        val reason: String get() = problem.english
    }
}

/** Why the Install field's text is not accepted. */
enum class SpecProblem(val english: String) {
    Empty("Enter a package name"),
    Spaces("Install one package at a time, without spaces"),
    Options("Options are not supported here"),
    Invalid("Use a package name, e.g. six or requests==2.32.3"),
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
            spec.isEmpty() -> SpecCheck.Invalid(SpecProblem.Empty)
            spec.any { it.isWhitespace() } -> SpecCheck.Invalid(SpecProblem.Spaces)
            spec.startsWith("-") -> SpecCheck.Invalid(SpecProblem.Options)
            !specRegex.matches(spec) -> SpecCheck.Invalid(SpecProblem.Invalid)
            else -> SpecCheck.Valid(spec)
        }
    }

    /** A bare distribution name (what `pip uninstall` gets). */
    fun isName(value: String): Boolean = nameRegex.matches(value)
}
