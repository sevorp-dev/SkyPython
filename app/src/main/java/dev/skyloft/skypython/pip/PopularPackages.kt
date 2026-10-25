package dev.skyloft.skypython.pip

import androidx.annotation.StringRes
import dev.skyloft.skypython.R

enum class Availability {
    /** Pure Python, including all dependencies. */
    Ready,
    /** Needs a prebuilt Android wheel that PyPI only has for 64-bit devices. */
    Needs64Bit,
    /** Needs native code that isn't available yet. */
    ComingLater,
}

data class PopularPackage(
    val name: String,
    @param:StringRes val summary: Int,
    val availability: Availability = Availability.Ready,
)

/**
 * The quick list on the Search tab. Each entry was checked against PyPI's JSON API: the
 * latest release (and every dependency) has a `py3-none-any` wheel, except where marked.
 * Left out: pyyaml and numpy (no Android wheel, and building needs a C compiler).
 */
object PopularPackages {

    val all: List<PopularPackage> = listOf(
        PopularPackage("requests", R.string.pip_pkg_requests),
        PopularPackage("rich", R.string.pip_pkg_rich),
        PopularPackage("colorama", R.string.pip_pkg_colorama),
        PopularPackage("beautifulsoup4", R.string.pip_pkg_beautifulsoup4),
        PopularPackage("python-dateutil", R.string.pip_pkg_dateutil),
        PopularPackage("tabulate", R.string.pip_pkg_tabulate),
        PopularPackage("tqdm", R.string.pip_pkg_tqdm),
        PopularPackage("httpx", R.string.pip_pkg_httpx),
        PopularPackage("click", R.string.pip_pkg_click),
        PopularPackage("art", R.string.pip_pkg_art),
        PopularPackage("pyfiglet", R.string.pip_pkg_pyfiglet),
        PopularPackage("termcolor", R.string.pip_pkg_termcolor),
        PopularPackage("emoji", R.string.pip_pkg_emoji),
        PopularPackage("faker", R.string.pip_pkg_faker),
        PopularPackage("sympy", R.string.pip_pkg_sympy),
        PopularPackage("mpmath", R.string.pip_pkg_mpmath),
        PopularPackage("networkx", R.string.pip_pkg_networkx),
        PopularPackage("prompt-toolkit", R.string.pip_pkg_prompt_toolkit),
        // markupsafe ships cp314 Android wheels for arm64-v8a and x86_64 only.
        PopularPackage("jinja2", R.string.pip_pkg_jinja2, Availability.Needs64Bit),
        PopularPackage("pillow", R.string.pip_pkg_pillow, Availability.ComingLater),
    )

    /** Entries whose normalized name contains the name part of [query]; all for a blank one. */
    fun matching(query: String): List<PopularPackage> {
        val q = PyPiJson.normalize(PackageSpec.nameOf(query.trim()))
        if (q.isEmpty()) return all
        return all.filter { q in PyPiJson.normalize(it.name) }
    }
}
