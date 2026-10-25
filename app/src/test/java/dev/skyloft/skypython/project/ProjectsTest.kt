package dev.skyloft.skypython.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentProjectsTest {
    @Test
    fun pushPutsTheNewestFirstWithoutDuplicates() {
        val list = listOf(RecentProject("/a", 1), RecentProject("/b", 2))
        val out = pushRecentProject(list, "/b", now = 10)
        assertEquals(listOf(RecentProject("/b", 10), RecentProject("/a", 1)), out)
    }

    @Test
    fun pushKeepsAtMostMax() {
        val many = (1..MAX_RECENT_PROJECTS).map { RecentProject("/$it", it.toLong()) }
        val out = pushRecentProject(many, "/new", now = 99)
        assertEquals(MAX_RECENT_PROJECTS, out.size)
        assertEquals("/new", out.first().path)
        assertEquals("/${MAX_RECENT_PROJECTS - 1}", out.last().path)
    }

    @Test
    fun forgetRemovesOnlyThatPath() {
        val list = listOf(RecentProject("/a", 1), RecentProject("/b", 2))
        assertEquals(listOf(RecentProject("/a", 1)), forgetRecentProject(list, "/b"))
    }

    @Test
    fun codecRoundTripsAndSkipsBrokenLines() {
        val list = listOf(RecentProject("/storage/emulated/0/Documents/My Bot", 1700000000000), RecentProject("/x\tweird%", 5))
        assertEquals(list, RecentProjectsCodec.decode(RecentProjectsCodec.encode(list)))
        assertEquals(emptyList<RecentProject>(), RecentProjectsCodec.decode(null))
        assertEquals(listOf(RecentProject("/a", 3)), RecentProjectsCodec.decode("garbage\n\n/a\t3\n/b\tnotanumber"))
    }
}

class StorageAccessTest {
    @Test
    fun android11AndNewerAskForAllFilesAccess() {
        assertEquals(StorageAccess.Request.AllFilesSettings, StorageAccess.requestFor(30))
        assertEquals(StorageAccess.Request.AllFilesSettings, StorageAccess.requestFor(36))
    }

    @Test
    fun android7To10AskForTheRuntimePermission() {
        assertEquals(StorageAccess.Request.RuntimePermission, StorageAccess.requestFor(24))
        assertEquals(StorageAccess.Request.RuntimePermission, StorageAccess.requestFor(29))
    }

    @Test
    fun grantedFollowsTheRightSignalPerVersion() {
        assertTrue(StorageAccess.granted(sdk = 33, allFilesAccess = true, writePermission = false))
        assertFalse(StorageAccess.granted(sdk = 33, allFilesAccess = false, writePermission = true))
        assertTrue(StorageAccess.granted(sdk = 24, allFilesAccess = false, writePermission = true))
        assertFalse(StorageAccess.granted(sdk = 29, allFilesAccess = true, writePermission = false))
    }

    @Test
    fun appPrivateFoldersNeedNoPermission() {
        val private = listOf(File("/data/user/0/app/files"), File("/storage/emulated/0/Android/data/app/files"))
        assertFalse(StorageAccess.needsPermission(File("/data/user/0/app/files/projects/demo"), private))
        assertFalse(StorageAccess.needsPermission(File("/data/user/0/app/files"), private))
        assertTrue(StorageAccess.needsPermission(File("/storage/emulated/0/Documents/MyBot"), private))
        assertTrue(StorageAccess.needsPermission(File("/data/user/0/app/filesX"), private))
    }
}

class StorageRootsTest {
    private val pkg = "dev.skyloft.skypython"

    @Test
    fun volumeRootStripsTheAppSpecificTail() {
        assertEquals(File("/storage/emulated/0"), StorageRoots.volumeRootOf(File("/storage/emulated/0/Android/data/$pkg/files")))
        assertEquals(File("/storage/1A2B-3C4D"), StorageRoots.volumeRootOf(File("/storage/1A2B-3C4D/Android/data/$pkg/files")))
        assertNull(StorageRoots.volumeRootOf(File("/data/user/0/$pkg/files")))
    }

    @Test
    fun firstVolumeIsInternalOthersAreCards() {
        val roots = StorageRoots.fromAppDirs(
            listOf(
                File("/storage/emulated/0/Android/data/$pkg/files"),
                null, // an SD card that is not mounted right now
                File("/storage/1A2B-3C4D/Android/data/$pkg/files"),
                File("/storage/emulated/0/Android/data/$pkg/files"),
            ),
        )
        assertEquals(
            listOf(
                StorageRoot(File("/storage/emulated/0"), StorageRoot.Kind.Internal),
                StorageRoot(File("/storage/1A2B-3C4D"), StorageRoot.Kind.SdCard),
            ),
            roots,
        )
    }

    @Test
    fun rootForPicksTheDeepestRootHoldingTheFolder() {
        val internal = StorageRoot(File("/storage/emulated/0"), StorageRoot.Kind.Internal)
        val card = StorageRoot(File("/storage/1A2B-3C4D"), StorageRoot.Kind.SdCard)
        val roots = listOf(internal, card)
        assertEquals(internal, StorageRoots.rootFor(File("/storage/emulated/0/Documents/x"), roots))
        assertEquals(card, StorageRoots.rootFor(File("/storage/1A2B-3C4D"), roots))
        assertNull(StorageRoots.rootFor(File("/storage/emulated/01"), roots))
    }

    @Test
    fun friendlyPathNamesTheVolumeInsteadOfItsMountPoint() {
        val roots = listOf(
            StorageRoot(File("/storage/emulated/0"), StorageRoot.Kind.Internal),
            StorageRoot(File("/storage/1A2B-3C4D"), StorageRoot.Kind.SdCard),
        )
        val label: (StorageRoot) -> String = { if (it.kind == StorageRoot.Kind.Internal) "Internal storage" else "SD card" }
        assertEquals("Internal storage/Documents/MyBot", friendlyPath(File("/storage/emulated/0/Documents/MyBot"), roots, label))
        assertEquals("SD card", friendlyPath(File("/storage/1A2B-3C4D"), roots, label))
        assertEquals(File("/data/x").path, friendlyPath(File("/data/x"), roots, label))
    }
}

class FolderListingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun foldersFirstThenNamesIgnoringCase() {
        val dir = tmp.newFolder("p")
        File(dir, "b.py").writeText("")
        File(dir, "A.txt").writeText("")
        File(dir, "zeta").mkdir()
        File(dir, "Beta").mkdir()
        val names = FolderListing.list(dir, showHidden = false).map { it.name }
        assertEquals(listOf("Beta", "zeta", "A.txt", "b.py"), names)
        assertTrue(FolderListing.list(dir, showHidden = false).first().isDirectory)
    }

    @Test
    fun hiddenEntriesOnlyWhenAsked() {
        val dir = tmp.newFolder("p")
        File(dir, ".git").mkdir()
        File(dir, ".env").writeText("")
        File(dir, "main.py").writeText("")
        assertEquals(listOf("main.py"), FolderListing.list(dir, showHidden = false).map { it.name })
        assertEquals(listOf(".git", ".env", "main.py"), FolderListing.list(dir, showHidden = true).map { it.name })
    }

    @Test
    fun missingOrUnlistableFolderIsEmpty() {
        assertEquals(emptyList<Any>(), FolderListing.list(File(tmp.root, "nope"), showHidden = true))
        val file = tmp.newFile("f.txt")
        assertEquals(emptyList<Any>(), FolderListing.list(file, showHidden = true))
    }

    @Test
    fun crumbsRunFromTheRootToTheFolder() {
        val root = File("/storage/emulated/0")
        val crumbs = FolderListing.crumbs(File("/storage/emulated/0/Documents/MyBot"), root)
        assertEquals(listOf(root, File("/storage/emulated/0/Documents"), File("/storage/emulated/0/Documents/MyBot")), crumbs.map { it.dir })
        assertEquals(listOf("", "Documents", "MyBot"), crumbs.map { it.label })
        assertEquals(listOf(root), FolderListing.crumbs(root, root).map { it.dir })
    }

    @Test
    fun parentStopsAtTheRoot() {
        val root = File("/storage/emulated/0")
        assertEquals(root, FolderListing.parentWithin(File("/storage/emulated/0/Documents"), root))
        assertNull(FolderListing.parentWithin(root, root))
    }
}

class ProjectCheckTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun existingFolderIsFine() {
        assertNull(ProjectCheck.problem(tmp.newFolder("ok")))
    }

    @Test
    fun missingFolderAndPlainFileAreReported() {
        assertEquals(ProjectCheck.Problem.Missing, ProjectCheck.problem(File(tmp.root, "gone")))
        assertEquals(ProjectCheck.Problem.NotAFolder, ProjectCheck.problem(tmp.newFile("x.py")))
    }

    @Test
    fun displayNameIsTheFolderNameOrTheLabelForThePrivateRoot() {
        val private = File("/data/user/0/app/files/projects")
        assertEquals("Projects", projectName(private, private, "Projects"))
        assertEquals("MyBot", projectName(File("/storage/emulated/0/Documents/MyBot"), private, "Projects"))
        assertEquals(File("/").path, projectName(File("/"), private, "Projects"))
    }
}

class ProjectStartupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val privateRoot by lazy { tmp.newFolder("projects") }
    private val needsPermission: (File) -> Boolean = { !it.path.startsWith(privateRoot.path) }

    @Test
    fun nothingSavedOpensThePrivateProjects() {
        val s = ProjectCheck.startup(null, privateRoot, needsPermission, granted = false)
        assertEquals(privateRoot, s.root)
        assertNull(s.problem)
    }

    @Test
    fun savedProjectReopensWhenReachable() {
        val bot = tmp.newFolder("MyBot")
        val s = ProjectCheck.startup(bot.path, privateRoot, needsPermission, granted = true)
        assertEquals(bot, s.root)
        assertNull(s.problem)
    }

    @Test
    fun revokedPermissionFallsBackWithAReason() {
        val bot = tmp.newFolder("MyBot")
        val s = ProjectCheck.startup(bot.path, privateRoot, needsPermission, granted = false)
        assertEquals(privateRoot, s.root)
        assertEquals(ProjectCheck.Problem.NoPermission, s.problem)
        assertEquals(bot, s.failed)
    }

    @Test
    fun vanishedFolderFallsBackWithAReason() {
        val s = ProjectCheck.startup(File(tmp.root, "gone").path, privateRoot, needsPermission, granted = true)
        assertEquals(privateRoot, s.root)
        assertEquals(ProjectCheck.Problem.Missing, s.problem)
    }
}
