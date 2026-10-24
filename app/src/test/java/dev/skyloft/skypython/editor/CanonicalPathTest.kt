package dev.skyloft.skypython.editor

import dev.skyloft.skypython.data.SavedTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Review finding #1: on Android `filesDir` is reached through a symlink (/data/user/0 -> /data/data),
 * so the same file can be named by a non-canonical and a canonical path. Tabs must treat both as one.
 */
class CanonicalPathTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A real root plus a second, non-canonical name for the same root. */
    private class Layout(val canonicalRoot: File, val aliasRoot: File)

    /** `root/sub/..` — always available, never skipped. */
    private fun dotDotLayout(): Layout {
        val root = tmp.newFolder("projects").canonicalFile
        File(root, "sub").mkdirs()
        return Layout(root, File(root, "sub/.."))
    }

    /** `link -> projects` — skipped only where the host can't create symlinks (e.g. Windows without dev mode). */
    private fun symlinkLayout(): Layout {
        val root = tmp.newFolder("linked-projects").canonicalFile
        val link = File(tmp.root, "link")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), root.toPath()) }.isSuccess
        assumeTrue("symlinks not permitted on this host", made)
        return Layout(root, link)
    }

    /** Both layouts; the symlink one is left out (not failed) only where symlinks can't be created. */
    private fun layouts(): List<Layout> = buildList {
        add(dotDotLayout())
        try {
            add(symlinkLayout())
        } catch (_: org.junit.AssumptionViolatedException) {
        }
    }

    private fun hello(l: Layout): Pair<File, File> {
        File(l.canonicalRoot, "hello.py").writeText("print('hi')")
        return File(l.aliasRoot, "hello.py") to File(l.canonicalRoot, "hello.py")
    }

    @Test
    fun sameFileViaNonCanonicalPathHasSameId_dotDot() = checkSameId(dotDotLayout())

    @Test
    fun sameFileViaNonCanonicalPathHasSameId_symlink() = checkSameId(symlinkLayout())

    private fun checkSameId(l: Layout) {
        val (alias, canonical) = hello(l)
        assertTrue(alias.path != canonical.path)
        val s = DocumentsState()
        s.open(OpenDocument.local(alias, "a"))
        s.open(OpenDocument.local(canonical, "b"))
        assertEquals("opening the canonical path must not add a duplicate tab", 1, s.docs.size)
        assertEquals(OpenDocument.idOf(DocSource.Local(alias)), OpenDocument.idOf(DocSource.Local(canonical)))
    }

    @Test
    fun closeLocalWithCanonicalPathClosesTabOpenedViaAlias() {
        for (l in layouts()) {
            val (alias, canonical) = hello(l)
            val s = DocumentsState()
            s.open(OpenDocument.local(alias, ""))
            s.closeLocal(canonical)
            assertTrue("file case", s.docs.isEmpty())
            s.open(OpenDocument.local(alias, ""))
            s.closeLocal(l.canonicalRoot) // deleting the folder
            assertTrue("folder case", s.docs.isEmpty())
        }
    }

    @Test
    fun renameWithCanonicalPathRepointsTabOpenedViaAlias() {
        for (l in layouts()) {
            val (alias, canonical) = hello(l)
            val s = DocumentsState()
            s.open(OpenDocument.local(alias, "x"))
            val target = File(l.canonicalRoot, "renamed.py")
            s.rename(canonical, target)
            assertEquals(listOf(OpenDocument.idOf(DocSource.Local(target))), s.docs.map { it.id })
        }
    }

    @Test
    fun labelIsRelativeToRootForDocOpenedViaAlias() {
        for (l in layouts()) {
            File(l.canonicalRoot, "a").mkdirs()
            File(l.canonicalRoot, "b").mkdirs()
            val docs = listOf(
                OpenDocument.local(File(l.aliasRoot, "a/main.py"), ""),
                OpenDocument.local(File(l.canonicalRoot, "b/main.py"), ""),
            )
            // The app may hand over a non-canonical root while Files hands over canonical paths.
            assertEquals("a" + File.separator + "main.py", tabLabel(docs[0], docs, l.aliasRoot))
            assertEquals("b" + File.separator + "main.py", tabLabel(docs[1], docs, l.aliasRoot))
        }
    }

    @Test
    fun restoredLocalTabWithOldNonCanonicalPathMapsToCanonicalId() {
        for (l in layouts()) {
            val (alias, canonical) = hello(l)
            val restored = restoreTabs(
                listOf(SavedTab(SavedTab.Kind.LOCAL, alias.path, "hello.py")),
                readLocal = { it.readText() },
                openExternal = { error("unused") },
            )
            val doc = restored.docs.single()!!
            assertEquals(OpenDocument.idOf(DocSource.Local(canonical)), doc.id)
            assertEquals(canonical.path, (doc.source as DocSource.Local).file.path)
        }
    }
}
