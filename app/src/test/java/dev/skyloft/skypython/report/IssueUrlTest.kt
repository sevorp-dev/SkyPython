package dev.skyloft.skypython.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class IssueUrlTest {

    private fun params(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    @Test fun `points at the bug report form with the bug label`() {
        val link = IssueUrl.build("Crash", "desc", "trace", "Pixel")
        assertTrue(link.url, link.url.startsWith("https://github.com/sevorp-dev/SkyPython/issues/new?"))
        val p = params(link.url)
        assertEquals("bug_report.yml", p["template"])
        assertEquals("bug", p["labels"])
        assertFalse(link.truncated)
    }

    @Test fun `every field round-trips through the encoding`() {
        val title = "Crash: a & b # c?d=e+f 100%"
        val description = "Нажал «Запуск» — и всё 🙃\nline two"
        val report = "at x.y(Z.kt:1)\n\tat a.b"
        val link = IssueUrl.build(title, description, report, "Model: Pixel 9")
        val p = params(link.url)
        assertEquals(title, p["title"])
        assertEquals(description, p["description"])
        assertEquals(report, p["report"])
        assertEquals("Model: Pixel 9", p["device"])
        // Spaces are %20 (a '+' would read as a literal plus in some browsers), no raw spaces or newlines.
        assertFalse(link.url, ' ' in link.url || '\n' in link.url || "+" in link.url.substringAfter("title="))
    }

    @Test fun `a long report is cut to fit the length cap`() {
        val huge = (1..5_000).joinToString("\n") { "\tat dev.skyloft.Frame$it.call(Frame.kt:$it)" }
        val link = IssueUrl.build("Crash", "what I did", huge, "Model: Pixel 9", maxLength = 7_000)
        assertTrue(link.url.length.toString(), link.url.length <= 7_000)
        assertTrue(link.truncated)
        val p = params(link.url)
        assertTrue(p["report"]!!.startsWith("\tat dev.skyloft.Frame1.call"))
        assertTrue(p["report"]!!.endsWith(IssueUrl.TRUNCATION_NOTE))
        // The user's words and the device survive the cut.
        assertEquals("what I did", p["description"])
        assertEquals("Model: Pixel 9", p["device"])
    }

    @Test fun `cuts at a line boundary`() {
        val huge = (1..2_000).joinToString("\n") { "line number $it" }
        val report = params(IssueUrl.build("t", "", huge, "", maxLength = 2_000).url)["report"]!!
        val kept = report.removeSuffix(IssueUrl.TRUNCATION_NOTE)
        assertTrue(kept, kept.lines().last().matches(Regex("line number \\d+")))
    }

    @Test fun `even a huge description and device fit`() {
        val link = IssueUrl.build("t", "д".repeat(10_000), "r".repeat(10_000), "x".repeat(10_000), maxLength = 3_000)
        assertTrue(link.url.length <= 3_000)
        assertTrue(link.truncated)
    }
}
