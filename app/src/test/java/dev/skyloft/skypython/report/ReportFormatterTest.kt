package dev.skyloft.skypython.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId

class ReportFormatterTest {

    private val cause = IOException("pipe closed")
    private val crash = IllegalStateException("Editor lost its document", cause)

    private val report = ProblemReport(
        id = "r1",
        kind = ReportKind.Crash,
        timeMillis = 1_761_400_000_000L,
        where = null,
        thread = "main",
        screen = "editor",
        message = crash.toString(),
        stackTrace = StackTraces.render(crash),
        appVersion = "0.0.3 (32)",
        log = (1..400).map { "log line $it" },
    )

    private val info = listOf(
        InfoSection("Device", listOf(InfoEntry("Model", "Pixel 9"), InfoEntry("Manufacturer", "Google"))),
        InfoSection("Mobile operator", listOf(InfoEntry("Network operator", "MegaFon"))),
    )

    private fun format(description: String = "Typed code", info: List<InfoSection>? = this.info) =
        ReportFormatter.format(report, description, info, ZoneId.of("UTC"))

    @Test fun `sections come in a fixed order`() {
        val text = format()
        val order = listOf("# SkyPython problem report", "## What were you doing?", "## Error", "## Device", "## Mobile operator", "## App log")
        val positions = order.map { text.indexOf(it) }
        assertTrue(text, positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test fun `header names the kind, time, version, thread and screen`() {
        val text = format()
        assertTrue(text, "Kind: Crash" in text)
        assertTrue(text, "Time: 2025-10-25 13:46:40 UTC" in text)
        assertTrue(text, "App: 0.0.3 (32)" in text)
        assertTrue(text, "Thread: main" in text)
        assertTrue(text, "Screen: editor" in text)
    }

    @Test fun `stack trace keeps every cause`() {
        val text = format()
        assertTrue(text, "java.lang.IllegalStateException: Editor lost its document" in text)
        assertTrue(text, "Caused by: java.io.IOException: pipe closed" in text)
        assertTrue(text, "ReportFormatterTest" in text) // frames, not just messages
    }

    @Test fun `stack trace rendering is capped`() {
        val deep = generateSequence(RuntimeException("root") as Throwable) { RuntimeException("wrap", it) }.take(500).last()
        val rendered = StackTraces.render(deep, maxChars = 5_000)
        assertTrue(rendered.length <= 5_000 + StackTraces.CUT_NOTE.length)
        assertTrue(rendered.endsWith(StackTraces.CUT_NOTE))
    }

    @Test fun `log section holds only the last 300 lines`() {
        val text = format()
        assertTrue(text, "log line 400" in text)
        assertTrue(text, "log line 101\n" in text)
        assertFalse(text, "log line 100\n" in text)
    }

    @Test fun `empty description is marked, not dropped`() {
        assertTrue("(not provided)" in format(description = "  "))
    }

    @Test fun `device info can be left out`() {
        val text = format(info = null)
        assertFalse(text, "## Device" in text)
        assertFalse(text, "Pixel 9" in text)
        assertTrue(text, "## Error" in text)
    }

    @Test fun `forbidden identifiers never reach the report`() {
        val leaky = listOf(
            InfoSection(
                "Device",
                listOf(
                    InfoEntry("Model", "Pixel 9"),
                    InfoEntry("IMEI", "490154203237518"),
                    InfoEntry("Phone number", "+79990001122"),
                    InfoEntry("Android ID", "9774d56d682e549c"),
                    InfoEntry("Advertising ID", "38400000-8cf0-11bd-b23e-10b96e40000d"),
                    InfoEntry("Serial", "EMULATOR35X1"),
                    InfoEntry("Account", "user@example.com"),
                    InfoEntry("Location", "55.75, 37.61"),
                    InfoEntry("Subscriber ID (IMSI)", "250020000000000"),
                ),
            ),
        )
        val text = ReportFormatter.format(report, "", leaky, ZoneId.of("UTC"))
        assertTrue(text, "Pixel 9" in text)
        for (secret in listOf("490154203237518", "+79990001122", "9774d56d682e549c", "38400000", "EMULATOR35X1", "user@example.com", "55.75", "250020000000000")) {
            assertFalse("leaked $secret", secret in text)
        }
    }

    @Test fun `title summarises the problem in one short line`() {
        val title = ReportFormatter.title(report)
        assertEquals("Crash: java.lang.IllegalStateException: Editor lost its document", title)
        val long = report.copy(message = "x".repeat(500))
        assertTrue(ReportFormatter.title(long).length <= ReportFormatter.TITLE_MAX)
        assertEquals("Problem report", ReportFormatter.title(report.copy(kind = ReportKind.Manual, message = null)))
        assertEquals(
            "Error (terminal): no pty",
            ReportFormatter.title(report.copy(kind = ReportKind.Error, where = "terminal", message = "no pty")),
        )
    }

    @Test fun `essentials keep the head of the trace and its causes`() {
        val text = ReportFormatter.essentials(report, maxStackLines = 3)
        assertTrue(text, "Kind: Crash" in text)
        assertTrue(text, "java.lang.IllegalStateException: Editor lost its document" in text)
        assertTrue(text, "Caused by: java.io.IOException: pipe closed" in text)
        assertTrue(text.lines().size < 20)
        assertFalse(text, "log line" in text)
    }

    @Test fun `device summary is short key value lines`() {
        val summary = ReportFormatter.deviceSummary(info)
        assertEquals("Model: Pixel 9\nManufacturer: Google\nNetwork operator: MegaFon", summary)
    }
}
