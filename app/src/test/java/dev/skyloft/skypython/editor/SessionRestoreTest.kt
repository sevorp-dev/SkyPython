package dev.skyloft.skypython.editor

import dev.skyloft.skypython.data.SavedTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException

/** Review finding #13c: a tab that can't be reopened must not vanish silently. */
class SessionRestoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun externalTabWithLostPermissionIsReported() {
        val local = tmp.newFile("a.py").apply { writeText("x") }
        val r = restoreTabs(
            listOf(
                SavedTab(SavedTab.Kind.LOCAL, local.path, "a.py"),
                SavedTab(SavedTab.Kind.EXTERNAL, "content://x/doc", "notes.py"),
            ),
            readLocal = { it.readText() },
            openExternal = { throw SecurityException("Permission Denial: opening provider") },
        )
        assertNotNull(r.docs[0])
        assertNull(r.docs[1])
        assertEquals(listOf("Couldn't reopen notes.py: permission lost"), r.messages)
    }

    @Test
    fun externalTabFailingForOtherReasonsIsReportedWithReason() {
        val r = restoreTabs(
            listOf(SavedTab(SavedTab.Kind.EXTERNAL, "content://x/doc", "gone.py")),
            readLocal = { error("unused") },
            openExternal = { throw FileNotFoundException("No such document") },
        )
        assertEquals(listOf<OpenDocument?>(null), r.docs)
        assertEquals(listOf("Couldn't reopen gone.py: No such document"), r.messages)
    }
}
