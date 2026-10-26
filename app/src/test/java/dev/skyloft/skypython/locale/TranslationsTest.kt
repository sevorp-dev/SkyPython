package dev.skyloft.skypython.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translation (res/values-xx) has every translatable English string, nothing extra, and
 * the same format placeholders (JVM tests run from the module directory). When English gains
 * strings, the failure message lists the missing keys per language, ready to translate.
 */
class TranslationsTest {

    private val res = File("src/main/res")

    /** One resource: its kind, translatability and the texts to compare placeholders on. */
    private data class Entry(val kind: String, val translatable: Boolean, val texts: Map<String, String>)

    private fun parseFolder(folder: File): Map<String, Entry> {
        val entries = LinkedHashMap<String, Entry>()
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            if (root.tagName != "resources") continue
            val children = root.childNodes
            for (i in 0 until children.length) {
                val el = children.item(i) as? Element ?: continue
                val name = el.getAttribute("name")
                val translatable = el.getAttribute("translatable") != "false"
                val texts: Map<String, String> = when (el.tagName) {
                    "string" -> mapOf("" to el.textContent)
                    "plurals" -> items(el, "item").associate { it.getAttribute("quantity") to it.textContent }
                    "string-array" -> items(el, "item").withIndex().associate { (n, it) -> "$n" to it.textContent }
                    else -> continue
                }
                val previous = entries.put(name, Entry(el.tagName, translatable, texts))
                if (previous != null) fail("${folder.name}/${file.name}: \"$name\" is defined twice")
            }
        }
        return entries
    }

    private fun items(el: Element, tag: String): List<Element> {
        val nodes = el.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private val english by lazy { parseFolder(File(res, "values")) }
    private val translatable by lazy { english.filterValues { it.translatable } }

    /** values-xx folders that hold strings, e.g. "ru", "pt-rBR", "zh-rCN". */
    private val locales by lazy {
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .filter { dir -> dir.listFiles().orEmpty().any { it.name.startsWith("strings") } }
            .associate { it.name.removePrefix("values-") to parseFolder(it) }
            .toSortedMap()
    }

    /** Format specifiers as "position:conversion", sorted; "%%" and "%n" don't count. */
    private fun placeholders(text: String): List<String> {
        var next = 1
        return Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?([a-zA-Z%])""").findAll(text)
            .mapNotNull { m ->
                val conversion = m.groupValues[3]
                if (conversion == "%" || conversion == "n") return@mapNotNull null
                val position = m.groupValues[1].removeSuffix("$").toIntOrNull() ?: next++
                "$position:${conversion.lowercase()}"
            }
            .sorted().toList()
    }

    @Test fun `there are translations to check`() {
        assertTrue("translations: ${locales.keys}", locales.keys.containsAll(listOf("ru", "uk", "es", "de", "pt-rBR", "tr", "zh-rCN")))
    }

    @Test fun `every language has every translatable string`() {
        val report = locales.mapNotNull { (locale, strings) ->
            val missing = translatable.keys.filter { it !in strings }
            if (missing.isEmpty()) null
            else "values-$locale is missing ${missing.size}:\n" +
                missing.joinToString("\n") { "    <${english.getValue(it).kind} name=\"$it\">${english.getValue(it).texts.values.first()}" }
        }
        if (report.isNotEmpty()) fail("Untranslated strings:\n" + report.joinToString("\n"))
    }

    @Test fun `no language has extra or non-translatable strings`() {
        val report = locales.mapNotNull { (locale, strings) ->
            val extra = strings.keys.filter { it !in translatable }
            if (extra.isEmpty()) null else "values-$locale: $extra"
        }
        if (report.isNotEmpty()) fail("Not in English or marked translatable=\"false\":\n" + report.joinToString("\n"))
    }

    @Test fun `translations keep the English placeholders and kinds`() {
        val report = mutableListOf<String>()
        for ((locale, strings) in locales) {
            for ((name, entry) in strings) {
                val source = translatable[name] ?: continue
                if (entry.kind != source.kind) {
                    report += "values-$locale/$name: ${entry.kind}, English has ${source.kind}"
                    continue
                }
                val expected = placeholders(source.texts["other"] ?: source.texts.values.first())
                for ((quantity, text) in entry.texts) {
                    val actual = placeholders(text)
                    // A plural "one" (or "zero"/"two") may spell the number out instead of using it.
                    val ok = actual == expected ||
                        (source.kind == "plurals" && quantity != "other" && expected.containsAll(actual))
                    if (!ok) report += "values-$locale/$name${if (quantity.isEmpty()) "" else "[$quantity]"}: $actual, English has $expected"
                }
                if (source.kind == "plurals" && "other" !in entry.texts) report += "values-$locale/$name: no quantity=\"other\""
                if (source.kind == "string-array" && entry.texts.size != source.texts.size) {
                    report += "values-$locale/$name: ${entry.texts.size} items, English has ${source.texts.size}"
                }
            }
        }
        if (report.isNotEmpty()) fail("Placeholder mismatches:\n" + report.joinToString("\n"))
    }

    @Test fun `apostrophes are escaped`() {
        val report = mutableListOf<String>()
        for ((locale, strings) in locales + ("" to english)) {
            for ((name, entry) in strings) {
                for (text in entry.texts.values) {
                    val quoted = text.length >= 2 && text.startsWith('"') && text.endsWith('"')
                    if (!quoted && Regex("""(?<!\\)'""").containsMatchIn(text)) report += "values${if (locale.isEmpty()) "" else "-$locale"}/$name: $text"
                }
            }
        }
        if (report.isNotEmpty()) fail("Unescaped ' (write \\'):\n" + report.joinToString("\n"))
    }

    @Test fun `settings languages, locale config and resource folders agree`() {
        val config = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "xml/locales_config.xml")).documentElement
        val configured = items(config, "locale").map { it.getAttribute("android:name") }.toSet()
        val offered = AppLanguage.entries.filter { it != AppLanguage.System }.map { it.tag }.toSet()
        assertEquals("locales_config.xml vs AppLanguage", offered, configured)
        val folders = locales.keys.map { it.replace("-r", "-") }.toSet() + "en"
        assertEquals("values-xx folders vs AppLanguage", offered, folders)
    }

    @Test fun `a stored or system tag maps to its language`() {
        assertEquals(AppLanguage.PortugueseBrazil, AppLanguage.fromTag("pt-BR"))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.Ukrainian, AppLanguage.fromTag("uk-UA,en-US"))
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.System, AppLanguage.fromTag("fr-FR"))
    }
}
