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

/** The headed groups of the popular list, in display order. */
enum class PopularSection(@param:StringRes val label: Int) {
    /** Whatever SkyPython's wheel index has for this device (filled in at runtime). */
    Prebuilt(R.string.pip_section_prebuilt),
    Popular(R.string.pip_section_popular),
    Data(R.string.pip_section_data),
    Web(R.string.pip_section_web),
    Text(R.string.pip_section_text),
}

data class PopularPackage(
    val name: String,
    @param:StringRes val summary: Int,
    val section: PopularSection,
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
        PopularPackage("requests", R.string.pip_pkg_requests, PopularSection.Popular),
        PopularPackage("rich", R.string.pip_pkg_rich, PopularSection.Popular),
        PopularPackage("tqdm", R.string.pip_pkg_tqdm, PopularSection.Popular),
        PopularPackage("click", R.string.pip_pkg_click, PopularSection.Popular),
        PopularPackage("colorama", R.string.pip_pkg_colorama, PopularSection.Popular),
        PopularPackage("python-dateutil", R.string.pip_pkg_dateutil, PopularSection.Popular),
        PopularPackage("sympy", R.string.pip_pkg_sympy, PopularSection.Data),
        PopularPackage("mpmath", R.string.pip_pkg_mpmath, PopularSection.Data),
        PopularPackage("networkx", R.string.pip_pkg_networkx, PopularSection.Data),
        PopularPackage("faker", R.string.pip_pkg_faker, PopularSection.Data),
        PopularPackage("pillow", R.string.pip_pkg_pillow, PopularSection.Data, Availability.ComingLater),
        // Installable from SkyPython's wheel index once it has a build for the device.
        PopularPackage("numpy", R.string.pip_pkg_numpy, PopularSection.Data, Availability.ComingLater),
        PopularPackage("matplotlib", R.string.pip_pkg_matplotlib, PopularSection.Data, Availability.ComingLater),
        PopularPackage("pandas", R.string.pip_pkg_pandas, PopularSection.Data, Availability.ComingLater),
        PopularPackage("httpx", R.string.pip_pkg_httpx, PopularSection.Web),
        PopularPackage("beautifulsoup4", R.string.pip_pkg_beautifulsoup4, PopularSection.Web),
        // markupsafe ships cp314 Android wheels for arm64-v8a and x86_64 only.
        PopularPackage("jinja2", R.string.pip_pkg_jinja2, PopularSection.Web, Availability.Needs64Bit),
        PopularPackage("tabulate", R.string.pip_pkg_tabulate, PopularSection.Text),
        PopularPackage("termcolor", R.string.pip_pkg_termcolor, PopularSection.Text),
        PopularPackage("prompt-toolkit", R.string.pip_pkg_prompt_toolkit, PopularSection.Text),
        PopularPackage("art", R.string.pip_pkg_art, PopularSection.Text),
        PopularPackage("pyfiglet", R.string.pip_pkg_pyfiglet, PopularSection.Text),
        PopularPackage("emoji", R.string.pip_pkg_emoji, PopularSection.Text),
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

    /**
     * [packages] under their headings, in [PopularSection] order, empty sections left out.
     * Those [prebuilt] for this device move up into [PopularSection.Prebuilt].
     */
    fun sections(
        packages: List<PopularPackage>,
        prebuilt: (PopularPackage) -> Boolean,
    ): List<Pair<PopularSection, List<PopularPackage>>> {
        val bySection = packages.groupBy { if (prebuilt(it)) PopularSection.Prebuilt else it.section }
        return PopularSection.entries.mapNotNull { section -> bySection[section]?.let { section to it } }
    }
}
