package dev.skyloft.skypython.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopularPackagesTest {

    private val all = PopularPackages.all

    @Test
    fun namesAreValidAndUnique() {
        assertTrue(all.size >= 15)
        for (p in all) assertTrue(p.name, PackageSpec.isName(p.name))
        assertEquals(all.size, all.map { PyPiJson.normalize(it.name) }.toSet().size)
    }

    @Test
    fun verifiedPurePackagesAreReady() {
        for (name in listOf("requests", "rich", "colorama", "beautifulsoup4", "python-dateutil", "tabulate", "tqdm",
            "httpx", "click", "art", "emoji", "faker", "sympy", "mpmath", "networkx", "pyfiglet", "termcolor",
            "prompt-toolkit")) {
            assertEquals(name, Availability.Ready, all.single { it.name == name }.availability)
        }
    }

    @Test
    fun nativeOnesAreMarked() {
        assertEquals(Availability.Needs64Bit, all.single { it.name == "jinja2" }.availability)
        assertEquals(Availability.ComingLater, all.single { it.name == "pillow" }.availability)
        // Shown as installable only when SkyPython's wheel index has it for the device.
        assertEquals(Availability.ComingLater, all.single { it.name == "numpy" }.availability)
        // No Android wheel and no pure wheel: left out rather than shown broken.
        assertTrue(all.none { it.name == "pyyaml" })
    }

    @Test
    fun sectionsKeepTheirOrderAndPrebuiltOnesMoveUp() {
        val sections = PopularPackages.sections(all) { it.name == "numpy" }
        assertEquals(PopularSection.entries, sections.map { it.first })
        assertEquals(listOf("numpy"), sections.first().second.map { it.name })
        assertEquals("requests", sections[1].second.first().name)
        assertTrue(sections.drop(1).none { (_, list) -> list.any { it.name == "numpy" } })
        assertEquals(all.size, sections.sumOf { it.second.size })
        // Nothing prebuilt (no index), and empty sections left out.
        val noIndex = PopularPackages.sections(PopularPackages.matching("numpy")) { false }
        assertEquals(listOf(PopularSection.Data), noIndex.map { it.first })
    }

    @Test
    fun matchingFiltersByNormalizedSubstring() {
        assertEquals(all, PopularPackages.matching("  "))
        assertEquals(listOf("python-dateutil"), PopularPackages.matching("DateUtil").map { it.name })
        assertEquals(listOf("prompt-toolkit"), PopularPackages.matching("prompt_tool").map { it.name })
        assertTrue(PopularPackages.matching("requests==2.0").any { it.name == "requests" })
        assertEquals(emptyList<PopularPackage>(), PopularPackages.matching("zzzz"))
    }
}
