package dev.skyloft.skypython.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class PendingReportsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun crash(n: Int) = ProblemReport(
        id = "id$n",
        kind = ReportKind.Crash,
        timeMillis = 1_000L * n,
        thread = "main",
        screen = "editor",
        message = "boom $n",
        stackTrace = "java.lang.RuntimeException: boom $n\n\tat a.b(C.kt:$n)",
        appVersion = "0.0.3 (32)",
        log = listOf("first", "second $n"),
    )

    @Test fun `round-trips every field`() {
        val store = PendingReports(tmp.newFolder("crash"))
        store.add(crash(1))
        assertEquals(listOf(crash(1)), store.list())
        assertEquals(crash(1), store.get("id1"))
    }

    @Test fun `keeps the newest five, newest first`() {
        val store = PendingReports(tmp.newFolder("crash"))
        (1..7).forEach { store.add(crash(it)) }
        assertEquals(listOf("id7", "id6", "id5", "id4", "id3"), store.list().map { it.id })
        assertEquals(5, store.dir.listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test fun `remove forgets one report`() {
        val store = PendingReports(tmp.newFolder("crash"))
        (1..3).forEach { store.add(crash(it)) }
        store.remove("id2")
        assertEquals(listOf("id3", "id1"), store.list().map { it.id })
        assertNull(store.get("id2"))
    }

    @Test fun `asks about a crash once`() {
        val store = PendingReports(tmp.newFolder("crash"))
        assertNull(store.unprompted())
        store.add(crash(1))
        store.add(crash(2))
        assertEquals("id2", store.unprompted()?.id)
        store.markPrompted()
        assertNull(store.unprompted())
        // Still listed for a later report.
        assertEquals(2, store.list().size)
        store.add(crash(3))
        assertEquals("id3", store.unprompted()?.id)
    }

    @Test fun `a broken file is skipped, not fatal`() {
        val dir = tmp.newFolder("crash")
        val store = PendingReports(dir)
        store.add(crash(1))
        File(dir, "99-broken.json").writeText("{ not json")
        assertEquals(listOf("id1"), store.list().map { it.id })
    }

    @Test fun `the crash handler saves the report and then lets the previous handler run`() {
        val store = PendingReports(tmp.newFolder("crash"))
        var delegated: Throwable? = null
        val handler = CrashHandler(
            store = store,
            previous = { _, e -> delegated = e },
            now = { 42_000L },
            appVersion = { "0.0.3 (32)" },
            screen = { "terminal" },
            logTail = { listOf("I/Test: before the crash") },
        )
        val error = IllegalStateException("boom", IllegalArgumentException("inner"))
        handler.uncaughtException(Thread.currentThread(), error)

        assertSame(error, delegated)
        val saved = store.list().single()
        assertEquals(ReportKind.Crash, saved.kind)
        assertEquals(42_000L, saved.timeMillis)
        assertEquals("terminal", saved.screen)
        assertEquals(Thread.currentThread().name, saved.thread)
        assertEquals(listOf("I/Test: before the crash"), saved.log)
        assertTrue(saved.stackTrace!!, "Caused by: java.lang.IllegalArgumentException: inner" in saved.stackTrace!!)
    }

    @Test fun `the crash handler still delegates if saving fails`() {
        val notADir = tmp.newFile("file")
        var delegated = false
        val handler = CrashHandler(
            store = PendingReports(File(notADir, "crash")),
            previous = { _, _ -> delegated = true },
            appVersion = { throw IllegalStateException("broken") },
        )
        handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertTrue(delegated)
    }

    @Test fun `file names use ASCII digits whatever the phone's language`() {
        val saved = Locale.getDefault()
        try {
            // Persian and Arabic phones format numbers with their own digits by default.
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            val store = PendingReports(tmp.newFolder("crash"))
            store.add(crash(1))
            store.add(crash(2))
            val names = store.dir.listFiles()!!.map { it.name }.filter { it.endsWith(".json") }
            assertTrue(names.toString(), names.all { Regex("[0-9]{15}-id[0-9][.]json").matches(it) })
            assertEquals(listOf("id2", "id1"), store.list().map { it.id })
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun `leftover temporary files of an interrupted save are cleaned up`() {
        val store = PendingReports(tmp.newFolder("crash"))
        store.add(crash(1))
        File(store.dir, "000000000002000-id2.tmp").writeText("{half")
        store.removeLeftovers()
        assertEquals(listOf("000000000001000-id1.json"), store.dir.listFiles()!!.map { it.name }.filter { it != "prompted" })
    }

    @Test fun `shared report files are kept for a while, each with its own name`() {
        val dir = tmp.newFolder("reports")
        val files = (1..5).map { Reports.newShareFile(dir, nowMillis = 1_700_000_000_000L).apply { writeText("r$it") } }
        assertEquals(5, files.map { it.name }.toSet().size)
        assertTrue("the one just shared stays", files.last().isFile)
        assertTrue(files.takeLast(Reports.SHARE_FILES_KEPT).all { it.isFile })
        assertTrue(files.dropLast(Reports.SHARE_FILES_KEPT).none { it.exists() })
    }
}
