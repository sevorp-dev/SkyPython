package dev.skyloft.skypython.files

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.skyloft.skypython.compat.EntryType
import dev.skyloft.skypython.compat.entryTypeNoFollow
import dev.skyloft.skypython.editor.resolvedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Symlink handling on a real device. On API 24/25 this runs the lstat-based code path
 * (no java.nio.file there), which the JVM unit tests can't reach.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceDeviceTest {

    private lateinit var base: File
    private lateinit var root: File
    private lateinit var outside: File
    private lateinit var ws: Workspace

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        base = File(context.cacheDir, "workspace-device-test").apply { deleteRecursively() }
        root = File(base, "projects").apply { mkdirs() }
        outside = File(base, "outside").apply { mkdirs() }
        ws = Workspace(root)
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun entryTypesDoNotFollowSymlinks() {
        val dir = File(root, "dir").apply { mkdirs() }
        val file = File(root, "f.py").apply { writeText("") }
        val dirLink = File(root, "dirLink").also { Os.symlink(outside.path, it.path) }
        val dangling = File(root, "dangling").also { Os.symlink(File(base, "missing").path, it.path) }

        assertEquals(EntryType.DIRECTORY, entryTypeNoFollow(dir))
        assertEquals(EntryType.OTHER, entryTypeNoFollow(file))
        assertEquals(EntryType.OTHER, entryTypeNoFollow(dirLink))
        assertEquals(EntryType.OTHER, entryTypeNoFollow(dangling))
        assertEquals(EntryType.MISSING, entryTypeNoFollow(File(root, "nope")))
        assertEquals(EntryType.MISSING, entryTypeNoFollow(File(file, "under-a-file")))
    }

    @Test
    fun deleteRemovesSymlinkedDirWithoutTouchingItsTarget() {
        val d = ws.createDir(root, "d").getOrThrow()
        val victim = File(outside, "victim.py").apply { writeText("keep") }
        Os.symlink(outside.path, File(d, "link").path)

        ws.delete(d).getOrThrow()

        assertFalse(d.exists())
        assertEquals("keep", victim.readText())
    }

    @Test
    fun danglingSymlinkCanBeRenamedAndDeleted() {
        Os.symlink(File(base, "missing").path, File(root, "dangling").path)

        val renamed = ws.rename(File(root, "dangling"), "renamed").getOrThrow()
        assertEquals(listOf("renamed"), root.list()!!.toList())
        ws.delete(renamed).getOrThrow()
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test
    fun resolvedFileFollowsSymlinksForExistingAndMissingFiles() {
        val link = File(base, "link").also { Os.symlink(root.path, it.path) }
        File(root, "a.py").writeText("")

        assertEquals(File(root.canonicalFile, "a.py"), resolvedFile(File(link, "a.py")))
        assertEquals(File(root.canonicalFile, "gone/b.py"), resolvedFile(File(link, "gone/b.py")))
    }
}
