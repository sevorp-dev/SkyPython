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

/** The right-hand side of a popular row (see [PopularPackages.action]). */
enum class PopularAction {
    Install,
    /** Install, with the "Prebuilt for Android" badge. */
    Prebuilt,
    ComingLater,
    Needs64Bit,
    /** In the wheel index, for 64-bit devices only. */
    Not32Bit,
    /** In the wheel index, but not for this device's ABI. */
    NotThisDevice,
}

data class PopularPackage(
    val name: String,
    @param:StringRes val summary: Int,
    val availability: Availability = Availability.Ready,
)

/**
 * The quick list on the Search tab. Each entry was checked against PyPI's JSON API: the
 * latest release (and every dependency) has a `py3-none-any` wheel, except where marked.
 * Left out: pyyaml (no Android wheel, and building needs a C compiler). numpy comes from
 * SkyPython's own wheel index ([WheelIndex]).
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
        // Installable from SkyPython's wheel index once it has a build for the device.
        PopularPackage("numpy", R.string.pip_pkg_numpy, Availability.ComingLater),
    )

    /**
     * What a row offers. A wheel in SkyPython's index for the device ([prebuilt]) wins over
     * [PopularPackage.availability]; one for other ABIs only says so.
     */
    fun action(pkg: PopularPackage, prebuilt: PrebuiltStatus?, is64Bit: Boolean): PopularAction = when {
        prebuilt is PrebuiltStatus.Available -> PopularAction.Prebuilt
        pkg.availability == Availability.Ready -> PopularAction.Install
        pkg.availability == Availability.Needs64Bit -> if (is64Bit) PopularAction.Install else PopularAction.Needs64Bit
        prebuilt is PrebuiltStatus.OtherAbis -> if (is64Bit) PopularAction.NotThisDevice else PopularAction.Not32Bit
        else -> PopularAction.ComingLater
    }

    /** Entries whose normalized name contains the name part of [query]; all for a blank one. */
    fun matching(query: String): List<PopularPackage> {
        val q = PyPiJson.normalize(PackageSpec.nameOf(query.trim()))
        if (q.isEmpty()) return all
        return all.filter { q in PyPiJson.normalize(it.name) }
    }
}
