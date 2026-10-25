package dev.skyloft.skypython.editor

import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the bundled TextMate assets parse and highlight Python (unit tests run with cwd = app/). */
class TextMateAssetsTest {
    private val dir = File("src/main/assets/textmate")

    @Test
    fun languagesJsonPointsAtExistingFiles() {
        val json = File(dir, "languages.json").readText()
        for (path in Regex("\"(textmate/[^\"]+)\"").findAll(json).map { it.groupValues[1] }) {
            assertTrue("missing $path", File(dir.parentFile, path).isFile)
        }
        assertTrue(json.contains("\"scopeName\": \"source.python\""))
    }

    @Test
    fun pythonGrammarTokenizesKeywordsAndStrings() {
        val grammarFile = File(dir, "python/syntaxes/python.tmLanguage.json")
        val registry = Registry(object : IRegistryOptions {
            override fun getGrammarSource(scopeName: String): IGrammarSource? =
                if (scopeName == "source.python") IGrammarSource.fromFile(grammarFile) else null
        })
        val grammar = registry.loadGrammar("source.python")
        assertNotNull(grammar)
        val line = "def greet(name): return f\"Hi {name}\"  # hello"
        val tokens = grammar!!.tokenizeLine(line).tokens
        val scopes = tokens.flatMap { it.scopes }.toSet()
        assertTrue(scopes.toString(), scopes.any { it.startsWith("storage.type.function.python") })
        assertTrue(scopes.toString(), scopes.any { it.startsWith("string.") })
        assertTrue(scopes.toString(), scopes.any { it.startsWith("comment.line.number-sign.python") })
    }

    @Test
    fun languageConfigurationLoads() {
        val conf = File(dir, "python/language-configuration.json").reader().use { LanguageConfiguration.load(it) }
        assertNotNull(conf)
        assertEquals("#", conf!!.comments!!.lineComment)
        assertTrue(conf.brackets!!.isNotEmpty())
    }

    @Test
    fun darkThemeLoadsWithTokenColors() {
        val registry = Registry()
        registry.setTheme(IThemeSource.fromFile(File(dir, "skypython-dark.json")))
        val colors = registry.colorMap.map { it.uppercase() }
        assertTrue(colors.toString(), "#F28C38" in colors) // control keywords orange
        assertTrue(colors.toString(), "#A8D48C" in colors) // strings soft green
        val raw = File(dir, "skypython-dark.json").readText()
        assertTrue(raw.contains("\"editor.background\": \"#1C2530\""))
    }

    @Test
    fun noticeListsEveryBundledThirdPartyFile() {
        val notice = File(dir, "NOTICE.md").readText()
        listOf("python.tmLanguage.json", "language-configuration.json", "skypython-dark.json").forEach {
            assertTrue("NOTICE.md missing $it", notice.contains(it))
        }
        assertTrue(File(dir, "python/LICENSE-MagicPython.txt").isFile)
        assertTrue(File(dir, "python/LICENSE-vscode.txt").isFile)
    }
}
