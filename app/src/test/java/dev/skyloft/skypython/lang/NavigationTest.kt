package dev.skyloft.skypython.lang

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NavigationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun json(s: String) = JsonParser.parseString(s)

    // ---- protocol ----

    @Test
    fun parsesDefinitions() {
        val defs = Protocol.definitions(json(
            """[{"name":"shout","type":"function","path":"/p/helper.py","kind":"project",
                "line":3,"column":4,"endColumn":9,"inFile":false},
               {"name":"print","type":"function","path":"/b/builtins.pyi","kind":"stdlib",
                "line":1,"column":0,"endColumn":5,"inFile":false},
               {"name":"get","type":"function","path":"/s/site-packages/requests/api.py","kind":"site-packages",
                "line":62,"column":4,"endColumn":7,"inFile":false},
               {"name":"x","type":"statement","path":null,"kind":"project","line":1,"column":0,"endColumn":1,"inFile":true}]""",
        ))!!
        assertEquals(Definition("shout", "function", "/p/helper.py", Origin.PROJECT, 3, 4, 9, false), defs[0])
        assertEquals(Origin.STDLIB, defs[1].origin)
        assertEquals(Origin.SITE_PACKAGES, defs[2].origin)
        assertNull(defs[3].path)
        assertTrue(defs[3].inFile)
    }

    @Test
    fun parsesUsagesOutlineAndSignature() {
        assertEquals(
            listOf(Usage(1, 0, 5), Usage(4, 6, 11)),
            Protocol.usages(json("""[{"line":1,"column":0,"endColumn":5},{"line":4,"column":6,"endColumn":11}]""")),
        )
        val outline = Protocol.outline(json(
            """[{"name":"Shape","kind":"class","line":3,"column":6,"endLine":9,"children":[
                 {"name":"area","kind":"method","line":4,"column":8,"endLine":5,"children":[]}]},
               {"name":"main","kind":"function","line":11,"column":10,"endLine":14,"children":[]}]""",
        ))!!
        assertEquals("Shape", outline[0].name)
        assertEquals(SymbolKind.CLASS, outline[0].kind)
        assertEquals(OutlineItem("area", SymbolKind.METHOD, 4, 8, 5), outline[0].children.single())
        assertEquals(SymbolKind.FUNCTION, outline[1].kind)

        val sig = Protocol.signature(json(
            """{"name":"print","params":["*values: object","sep: str | None=\" \""],"short":["*values","sep=\" \""],"index":1}""",
        ))!!
        assertEquals(Signature("print", listOf("*values: object", "sep: str | None=\" \""), listOf("*values", "sep=\" \""), 1), sig)
        assertNull(Protocol.signature(null))
        assertNull(Protocol.signature(json("""{"name":"f","params":[],"short":[],"index":null}"""))!!.index)
    }

    @Test
    fun malformedResultsAreNull() {
        assertNull(Protocol.definitions(json("""[{"name":"x"}]""")))
        assertNull(Protocol.outline(json("""{"not":"a list"}""")))
        assertNull(Protocol.signature(json("""[1]""")))
    }

    // ---- where a definition opens ----

    private fun def(path: String?, origin: Origin, inFile: Boolean = false) =
        Definition("name", "function", path, origin, 3, 4, 8, inFile)

    @Test
    fun sameFileJumpsAndSelectsTheName() {
        val t = Navigation.target(def(null, Origin.PROJECT, inFile = true), tmp.root)
        assertEquals(NavTarget.InFile(EditorRange(2, 4, 2, 8)), t)
    }

    @Test
    fun anotherProjectFileOpensForEditing() {
        val helper = tmp.newFolder("proj").resolve("helper.py").apply { writeText("") }
        val t = Navigation.target(def(helper.path, Origin.PROJECT), tmp.root) as NavTarget.OtherFile
        assertEquals(helper.canonicalFile, t.file.canonicalFile)
        assertFalse(t.readOnly)
        assertEquals(EditorRange(2, 4, 2, 8), t.range)
    }

    @Test
    fun libraryAndOutsideFilesOpenReadOnly() {
        val outside = tmp.newFolder("elsewhere").resolve("thing.py").apply { writeText("") }
        val projects = tmp.newFolder("projects")
        assertTrue((Navigation.target(def(outside.path, Origin.PROJECT), projects) as NavTarget.OtherFile).readOnly)
        assertTrue((Navigation.target(def("/x/json/__init__.py", Origin.STDLIB), projects) as NavTarget.OtherFile).readOnly)
        val inProjects = projects.resolve("site-packages-ish.py").path
        assertTrue((Navigation.target(def(inProjects, Origin.SITE_PACKAGES), projects) as NavTarget.OtherFile).readOnly)
    }

    @Test
    fun noPathAndNotInFileIsNowhere() {
        assertNull(Navigation.target(def(null, Origin.STDLIB), tmp.root))
    }

    // ---- signature hint ----

    @Test
    fun hintBoldsTheCurrentParameter() {
        val sig = Signature("print", emptyList(), listOf("*values", "sep=' '", "end='\\n'"), 1)
        val h = SignatureHints.hint(sig)
        assertEquals("print(*values, sep=' ', end='\\n')", h.text)
        assertEquals("sep=' '", h.text.substring(h.boldStart, h.boldEnd))
    }

    @Test
    fun hintWithoutACurrentParameterHasNoBold() {
        val h = SignatureHints.hint(Signature("f", emptyList(), emptyList(), null))
        assertEquals("f()", h.text)
        assertEquals(h.boldStart, h.boldEnd)
        val past = SignatureHints.hint(Signature("g", emptyList(), listOf("a"), 3))
        assertEquals(past.boldStart, past.boldEnd)
    }

    @Test
    fun hintFallsBackToFullParamsWhenShortIsMissing() {
        val h = SignatureHints.hint(Signature("f", listOf("a: int"), emptyList(), 0))
        assertEquals("f(a: int)", h.text)
    }

    @Test
    fun insideACallOnlyWithAnUnclosedParen() {
        assertTrue(SignatureHints.mayBeInCall("print("))
        assertTrue(SignatureHints.mayBeInCall("x = max(1, [2, "))
        assertTrue(SignatureHints.mayBeInCall("f(a,\n  b"))
        assertFalse(SignatureHints.mayBeInCall("print()"))
        assertFalse(SignatureHints.mayBeInCall("x = 1"))
        assertFalse(SignatureHints.mayBeInCall("s = '('"))
        assertFalse(SignatureHints.mayBeInCall("# call("))
        assertTrue(SignatureHints.mayBeInCall("print(')', "))
        assertFalse(SignatureHints.mayBeInCall("x = [1, 2"))
        assertFalse(SignatureHints.mayBeInCall("s = \"\"\"(\n"))
    }

    @Test
    fun wordUnderTheCursor() {
        assertEquals("shout", dev.skyloft.skypython.editor.wordAt("x = shout('hi')", 6))
        assertEquals("shout", dev.skyloft.skypython.editor.wordAt("x = shout('hi')", 9)) // right after it
        assertEquals("имя", dev.skyloft.skypython.editor.wordAt("имя = 1", 0))
        assertNull(dev.skyloft.skypython.editor.wordAt("x = 12345", 6))
        assertNull(dev.skyloft.skypython.editor.wordAt("x  = 1", 2))
        assertNull(dev.skyloft.skypython.editor.wordAt("", 0))
    }

    // ---- outline ----

    private val outline = listOf(
        OutlineItem("Shape", SymbolKind.CLASS, 3, 6, 9, listOf(
            OutlineItem("area", SymbolKind.METHOD, 4, 8, 5),
            OutlineItem("Meta", SymbolKind.CLASS, 7, 10, 9, listOf(OutlineItem("deep", SymbolKind.METHOD, 8, 12, 9))),
        )),
        OutlineItem("main", SymbolKind.FUNCTION, 11, 10, 14),
    )

    @Test
    fun flattenKeepsOrderAndDepth() {
        assertEquals(
            listOf("Shape" to 0, "area" to 1, "Meta" to 1, "deep" to 2, "main" to 0),
            Outlines.flatten(outline).map { it.item.name to it.depth },
        )
    }

    @Test
    fun symbolAtALine() {
        assertEquals("Shape.area", Outlines.symbolAt(outline, 5))
        assertEquals("Shape.Meta.deep", Outlines.symbolAt(outline, 9))
        assertEquals("Shape", Outlines.symbolAt(outline, 6))
        assertEquals("main", Outlines.symbolAt(outline, 11))
        assertNull(Outlines.symbolAt(outline, 1))
        assertNull(Outlines.symbolAt(outline, 15))
    }
}
