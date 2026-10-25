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
        assertEquals(listOf("total", "total"), FindQuery("\\bt\\w+l\\b", matchCase = true, regex = true).matches())
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
        assertEquals("\$9", q.replacementFor("ab", 0, 1, "\$9"))
    }

    @Test
    fun replacementForOneMatch() {
        assertEquals("sum", FindQuery("total").replacementFor("a total", 2, 7, "sum"))
        assertEquals("b-a", FindQuery("(a)(b)", regex = true).replacementFor("xab", 1, 3, "$2-$1"))
    }

    /** Replace, one match at a time from the start, must give what Replace all gives. */
    private fun FindQuery.replaceOneByOne(input: String, replacement: String): String {
        var text = input
        var from = 0
        while (true) {
            val m = compile()!!.matcher(text)
            if (!m.find(from)) return text
            if (m.end() == m.start()) { from = m.end() + 1; if (from > text.length) return text; continue }
            val r = replacementFor(text, m.start(), m.end(), replacement)
            text = text.substring(0, m.start()) + r + text.substring(m.end())
            from = m.start() + r.length
        }
    }

    @Test
    fun replaceSeesTheTextAroundTheMatchLikeReplaceAll() {
        val cases = listOf(
            FindQuery("(?<=(x))a", regex = true) to ("xa ya xa" to "[$1]"), // lookbehind with a group
            FindQuery("a(?=b)", regex = true) to ("ab ac ab" to "<$0>"), // lookahead
            FindQuery("^(\\w)", regex = true) to ("one\ntwo" to "$1$1"), // anchor
            FindQuery("(o)\\b", regex = true) to ("foo food" to "0"), // word boundary
        )
        for ((q, io) in cases) {
            val (input, replacement) = io
            assertEquals(q.pattern, q.replaceAll(input, replacement).first, q.replaceOneByOne(input, replacement))
        }
        val q = FindQuery("(?<=(x))a", regex = true)
        assertEquals("[x]", q.replacementFor("xa", 1, 2, "[$1]"))
        assertEquals("<a>", FindQuery("a(?=b)", regex = true).replacementFor("ab", 0, 1, "<$0>"))
    }

    @Test
    fun aRunawayPatternCanBeStopped() {
        // A pattern that backtracks a lot over a big file: the check runs while the regex reads
        // the text, so a timeout or a closed find bar stops it mid-match.
        val q = FindQuery("(x+x+)+y", regex = true)
        val input = "x".repeat(200_000)
        var checks = 0
        try {
            q.replaceAll(input, "z") { if (++checks == 3) throw java.util.concurrent.CancellationException("timeout") }
            org.junit.Assert.fail("expected the replace to be stopped")
        } catch (e: java.util.concurrent.CancellationException) {
            assertEquals(3, checks)
        }
    }

    @Test
    fun noMatchesLeaveTextUnchanged() {
        assertEquals(doc to 0, FindQuery("zzz").replaceAll(doc, "y"))
    }

    @Test
    fun anInvalidPatternIsShownAfterALocalizedLabel() {
        val q = FindQuery("(unclosed", regex = true)
        val detail = q.error!!
        assertEquals("Ошибка в выражении: $detail", findErrorText("Ошибка в выражении", detail))
        assertEquals("Ошибка в выражении", findErrorText("Ошибка в выражении", ""))
    }

    @Test
    fun counterLabel() {
        assertEquals("0", matchCounter(-1, 0))
        assertEquals("3/12", matchCounter(2, 12))
        assertEquals("–/12", matchCounter(-1, 12))
        assertTrue(matchCounter(12, 12).startsWith("–"))
    }
}
