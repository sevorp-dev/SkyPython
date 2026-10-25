package dev.skyloft.skypython.editor

import dev.skyloft.skypython.data.SavedTab
import dev.skyloft.skypython.data.Session
import dev.skyloft.skypython.data.SessionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure document-tab logic behind EditorViewModel (no Android dependencies). */
class EditorViewModelTest {

    private fun doc(path: String, text: String = "") =
        OpenDocument.local(File(path), text)

    @Test
    fun openingSameFileTwiceDoesNotDuplicate() {
        val s = DocumentsState()
        s.open(doc("/p/a.py", "one"))
        s.open(doc("/p/b.py"))
        val again = s.open(doc("/p/a.py", "two"))
        assertEquals(listOf("a.py", "b.py"), s.docs.map { it.name })
        assertEquals("one", again.text) // existing doc kept, not replaced
        assertEquals(again.id, s.currentId) // and selected
    }

    @Test
    fun openSelectsNewDoc() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py"))
        assertEquals(a.id, s.currentId)
        val b = s.open(doc("/p/b.py"))
        assertEquals(b.id, s.currentId)
        assertSame(b, s.current)
    }

    @Test
    fun closeCurrentSelectsRightNeighbour() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py"))
        val b = s.open(doc("/p/b.py"))
        val c = s.open(doc("/p/c.py"))
        s.select(b.id)
        s.close(b.id)
        assertEquals(c.id, s.currentId)
        assertEquals(listOf(a.id, c.id), s.docs.map { it.id })
    }

    @Test
    fun closeLastCurrentSelectsLeftNeighbour() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py"))
        val b = s.open(doc("/p/b.py"))
        s.close(b.id)
        assertEquals(a.id, s.currentId)
        s.close(a.id)
        assertNull(s.currentId)
        assertTrue(s.docs.isEmpty())
    }

    @Test
    fun closeNonCurrentKeepsSelection() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py"))
        val b = s.open(doc("/p/b.py"))
        s.close(a.id)
        assertEquals(b.id, s.currentId)
    }

    @Test
    fun updateSetsDirtyAndMarkSavedClearsIt() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py", "x"))
        assertFalse(s.find(a.id)!!.dirty)
        s.update(a.id, "x = 1")
        assertTrue(s.find(a.id)!!.dirty)
        assertEquals("x = 1", s.find(a.id)!!.text)
        s.markSaved(a.id)
        assertFalse(s.find(a.id)!!.dirty)
    }

    @Test
    fun updateWithSameTextDoesNotDirty() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py", "x"))
        s.update(a.id, "x")
        assertFalse(s.find(a.id)!!.dirty)
    }

    @Test
    fun markSavedWithStaleTextKeepsDirty() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py", ""))
        s.update(a.id, "v1")
        s.update(a.id, "v2") // user typed while v1 was being written
        s.markSaved(a.id, savedText = "v1")
        assertTrue(s.find(a.id)!!.dirty)
        s.markSaved(a.id, savedText = "v2")
        assertFalse(s.find(a.id)!!.dirty)
    }

    @Test
    fun closeLocalClosesFileAndEverythingUnderDeletedDir() {
        val s = DocumentsState()
        s.open(doc("/p/dir/a.py"))
        s.open(doc("/p/dir/sub/b.py"))
        s.open(doc("/p/dir2/c.py"))
        s.open(doc("/p/x.py"))
        s.closeLocal(File("/p/dir"))
        assertEquals(listOf("c.py", "x.py"), s.docs.map { it.name })
        s.closeLocal(File("/p/x.py"))
        assertEquals(listOf("c.py"), s.docs.map { it.name })
    }

    @Test
    fun replaceSwapsDocumentInPlaceAndKeepsSelection() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py", "t"))
        val b = s.open(doc("/p/b.py"))
        val renamed = doc("/p/z.py", "t")
        s.replace(a.id, renamed)
        assertEquals(listOf(renamed.id, b.id), s.docs.map { it.id })
        assertEquals(b.id, s.currentId)
        s.select(renamed.id)
        s.replace(renamed.id, doc("/p/y.py"))
        assertEquals(OpenDocument.idOf(DocSource.Local(File("/p/y.py"))), s.currentId)
    }

    // --- rename re-points tabs (review finding #6) ---

    private fun id(path: String) = OpenDocument.idOf(DocSource.Local(File(path)))

    @Test
    fun renameFileRepointsTabKeepingTextDirtyPositionAndSelection() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py", "old"))
        s.open(doc("/p/b.py"))
        s.update(a.id, "edited")
        s.select(a.id)
        s.rename(File("/p/a.py"), File("/p/z.py"))
        assertEquals(listOf(id("/p/z.py"), id("/p/b.py")), s.docs.map { it.id })
        val z = s.find(id("/p/z.py"))!!
        assertEquals("z.py", z.name)
        assertEquals("edited", z.text)
        assertTrue(z.dirty)
        assertEquals(File("/p/z.py").canonicalPath, (z.source as DocSource.Local).file.path)
        assertEquals(z.id, s.currentId)
    }

    @Test
    fun renameFolderRepointsEveryTabUnderItAndLeavesOthers() {
        val s = DocumentsState()
        val a = s.open(doc("/p/dir/a.py", "A"))
        s.open(doc("/p/dir/sub/b.py", "B"))
        s.open(doc("/p/dir2/c.py", "C"))
        s.update(a.id, "A2")
        s.rename(File("/p/dir"), File("/p/new"))
        assertEquals(
            listOf(id("/p/new/a.py"), id("/p/new/sub/b.py"), id("/p/dir2/c.py")),
            s.docs.map { it.id },
        )
        assertEquals(listOf("a.py", "b.py", "c.py"), s.docs.map { it.name })
        assertEquals(listOf("A2", "B", "C"), s.docs.map { it.text })
        assertEquals(listOf(true, false, false), s.docs.map { it.dirty })
        assertEquals(id("/p/dir2/c.py"), s.currentId) // selection untouched
    }

    @Test
    fun renameFolderMovesSelectionWithTheSelectedTab() {
        val s = DocumentsState()
        s.open(doc("/p/x.py"))
        s.open(doc("/p/dir/a.py"))
        s.rename(File("/p/dir"), File("/p/d2"))
        assertEquals(id("/p/d2/a.py"), s.currentId)
    }

    @Test
    fun selectUnknownIdIsIgnored() {
        val s = DocumentsState()
        val a = s.open(doc("/p/a.py"))
        s.select("nope")
        assertEquals(a.id, s.currentId)
    }

    // --- read-only library tabs ---

    @Test
    fun readOnlyDocsNeverBecomeDirty() {
        val s = DocumentsState()
        val lib = s.open(OpenDocument.local(File("/lib/json/__init__.py"), "x", readOnly = true))
        assertTrue(lib.readOnly)
        s.update(lib.id, "changed")
        assertFalse(s.find(lib.id)!!.dirty)
        assertEquals("x", s.find(lib.id)!!.text)
    }

    @Test
    fun sessionLeavesReadOnlyTabsOut() {
        val a = doc("/p/a.py")
        val lib = OpenDocument.local(File("/lib/os.py"), "", readOnly = true)
        val b = doc("/p/b.py")
        val s = sessionOf(listOf(a, lib, b), currentId = b.id)
        assertEquals(listOf("a.py", "b.py"), s.tabs.map { it.name })
        assertEquals(1, s.currentIndex)
        // The library tab was current: the session falls back to the tab before it.
        assertEquals(0, sessionOf(listOf(a, lib, b), currentId = lib.id).currentIndex)
    }

    // --- session persistence codec ---

    @Test
    fun sessionRoundTrips() {
        val session = Session(
            tabs = listOf(
                SavedTab(SavedTab.Kind.LOCAL, "/data/projects/my dir/a b.py", "a b.py"),
                SavedTab(SavedTab.Kind.EXTERNAL, "content://x/doc%2F1", "weird\tname\n%.py"),
            ),
            currentIndex = 1,
        )
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun sessionDecodeToleratesNullAndGarbage() {
        assertNull(SessionCodec.decode(null))
        val s = SessionCodec.decode("garbage\nL\t/a.py\ta.py\nZ\tq\tq")!!
        assertEquals(listOf(SavedTab(SavedTab.Kind.LOCAL, "/a.py", "a.py")), s.tabs)
        assertEquals(-1, s.currentIndex)
    }

    @Test
    fun emptySessionRoundTripsAsEmptyNotNull() {
        val empty = Session(emptyList(), -1)
        assertEquals(empty, SessionCodec.decode(SessionCodec.encode(empty)))
    }

    // A project file shown read-only (Go to definition found it as library code, e.g. through a
    // path jedi didn't see as the project) and then opened from Files: the user asked to edit it.

    @Test
    fun aReadOnlyTabOfAProjectFileBecomesEditableWhenOpenedNormally() {
        val s = DocumentsState()
        val shown = s.open(OpenDocument.local(File("/p/lib.py"), "x = 1", readOnly = true))
        s.open(doc("/p/main.py"))
        assertTrue(reopenAsEditable(shown, readOnly = false, insideProjects = true))
        s.makeEditable(shown.id)
        val now = s.find(shown.id)!!
        assertFalse(now.readOnly)
        assertEquals("x = 1", now.text)
        assertFalse(now.dirty)
        assertEquals(listOf("lib.py", "main.py"), s.docs.map { it.name }) // same tab, same place
        s.update(shown.id, "x = 2")
        assertTrue(s.find(shown.id)!!.dirty)
    }

    @Test
    fun libraryCodeOutsideTheProjectsStaysReadOnly() {
        val shown = OpenDocument.local(File("/usr/lib/python3.14/json/__init__.py"), "", readOnly = true)
        assertFalse(reopenAsEditable(shown, readOnly = false, insideProjects = false))
    }

    @Test
    fun goToDefinitionNeverMakesAnEditableTabReadOnly() {
        val open = doc("/p/main.py")
        assertFalse(reopenAsEditable(open, readOnly = true, insideProjects = true))
        assertFalse(reopenAsEditable(open, readOnly = false, insideProjects = true)) // already editable
    }
}
