package dev.skyloft.skypython.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindQueryTest {
    private val doc = "Total = total + 1\nprint(total)  # totally\n"

    private fun FindQuery.matches(s: String = doc) = findAll(s).map { s.substring(it.first, it.last + 1) }

    @Test
    fun emptyQueryFindsNothing() {
        assertNull(FindQuery("").pattern)
        assertFalse(FindQuery("").isSearchable())
        assertEquals(emptyList<IntRange>(), FindQuery("").findAll(doc))
    }

    @Test
    fun plainSearchIgnoresCaseByDefault() {
        assertEquals(listOf("Total", "total", "total", "total"), FindQuery("total").matches())
    }

    @Test
    fun matchCaseIsRespected() {
        assertEquals(listOf("total", "total", "total"), FindQuery("total", matchCase = true).matches())
        assertEquals(listOf("Total"), FindQuery("Total", matchCase = true).matches())
    }

    @Test
    fun wholeWordSkipsPartOfLongerWords() {
        assertEquals(listOf("Total", "total", "total"), FindQuery("total", wholeWord = true).matches())
    }

    @Test
    fun plainTextIsNotARegex() {
        assertEquals(listOf("("), FindQuery("(").matches())
        assertEquals(listOf("+ 1"), FindQuery("+ 1").matches())
        assertNull(FindQuery("(").error)
    }

    @Test
    fun regexModeUsesThePattern() {
        assertEquals(listOf("total", "total"), FindQuery("t\\w+l\\b", matchCase = true, regex = true).matches())
        assertEquals(listOf("print"), FindQuery("^p\\w+", regex = true).matches())
    }

    @Test
    fun regexWithWholeWord() {
        assertEquals(listOf("Total", "total", "total"), FindQuery("tot\\w+", regex = true, wholeWord = true).matches().take(3))
        assertEquals(listOf("total"), FindQuery("t.tal", regex = true, wholeWord = true, matchCase = true).matches("total totals"))
    }

    @Test
    fun invalidRegexReportsAnErrorAndFindsNothing() {
        val q = FindQuery("(abc", regex = true)
        assertNotNull(q.error)
        assertFalse(q.isSearchable())
        assertEquals(emptyList<IntRange>(), q.findAll(doc))
        assertEquals(doc to 0, q.replaceAll(doc, "x"))
    }

    @Test
    fun emptyMatchesAreSkipped() {
        assertEquals(listOf("aa"), FindQuery("a*", regex = true).matches("baab"))
    }

    @Test
    fun replaceAllLiteral() {
        val (out, n) = FindQuery("total", wholeWord = true, matchCase = true).replaceAll(doc, "sum")
        assertEquals("Total = sum + 1\nprint(sum)  # totally\n", out)
        assertEquals(2, n)
    }

    @Test
    fun literalReplacementKeepsDollarSigns() {
        assertEquals("cost: \$1" to 1, FindQuery("x").replaceAll("cost: x", "\$1"))
    }

    @Test
    fun replaceAllRegexExpandsGroups() {
        val q = FindQuery("(\\w+)\\((\\w+)\\)", regex = true)
        assertEquals("total.print" to 1, q.replaceAll("print(total)", "$2.$1"))
    }

    @Test
    fun badGroupReferenceIsInsertedAsTyped() {
        val q = FindQuery("a", regex = true)
        assertEquals("\$9b" to 1, q.replaceAll("ab", "\$9"))
        assertEquals("\$9", q.replacementFor("a", "\$9"))
    }

    @Test
    fun replacementForOneMatch() {
        assertEquals("sum", FindQuery("total").replacementFor("total", "sum"))
        assertEquals("b-a", FindQuery("(a)(b)", regex = true).replacementFor("ab", "$2-$1"))
    }

    @Test
    fun noMatchesLeaveTextUnchanged() {
        assertEquals(doc to 0, FindQuery("zzz").replaceAll(doc, "y"))
    }

    @Test
    fun counterLabel() {
        assertEquals("0", matchCounter(-1, 0))
        assertEquals("3/12", matchCounter(2, 12))
        assertEquals("–/12", matchCounter(-1, 12))
        assertTrue(matchCounter(12, 12).startsWith("–"))
    }
}
