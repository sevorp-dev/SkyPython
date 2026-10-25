package dev.skyloft.skypython

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What Android backs up (JVM tests run from the module directory). Crash reports hold logs and
 * stack traces and must not travel to the cloud or another phone; the unpacked runtime, language
 * server and debugger are re-created from the APK.
 */
class BackupRulesTest {

    private val notBackedUp = listOf("python/", "home/", "crash/", "langserver/", "debugger/")

    private fun excludes(section: Element): Set<String> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    private fun parse(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$name")).documentElement

    @Test fun `android 6 to 11 full backup leaves out private and re-creatable folders`() {
        val excluded = excludes(parse("backup_rules.xml"))
        for (path in notBackedUp) assertTrue("$path in $excluded", path in excluded)
    }

    @Test fun `android 12 cloud backup and device transfer leave them out too`() {
        val root = parse("data_extraction_rules.xml")
        for (tag in listOf("cloud-backup", "device-transfer")) {
            val section = root.getElementsByTagName(tag).item(0) as Element
            val excluded = excludes(section)
            for (path in notBackedUp) assertTrue("$tag: $path in $excluded", path in excluded)
        }
    }
}
