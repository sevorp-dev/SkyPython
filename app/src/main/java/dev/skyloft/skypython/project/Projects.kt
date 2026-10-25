package dev.skyloft.skypython.project

import dev.skyloft.skypython.files.FileEntry
import java.io.File
import java.io.IOException

// Pure logic of "Open project": recent projects, storage roots, the folder browser's listing and
// the checks made before a folder becomes the project. No Android types: unit-tested on the JVM.

const val MAX_RECENT_PROJECTS = 12

/** A project folder opened before: its absolute [path] and when it was last opened (epoch ms). */
data class RecentProject(val path: String, val openedAt: Long)

/** [path] moved to the front with [now] as its time, without duplicates, at most [max] entries. */
fun pushRecentProject(
    list: List<RecentProject>,
    path: String,
    now: Long,
    max: Int = MAX_RECENT_PROJECTS,
): List<RecentProject> = (listOf(RecentProject(path, now)) + list.filter { it.path != path }).take(max)

fun forgetRecentProject(list: List<RecentProject>, path: String): List<RecentProject> = list.filter { it.path != path }

/** One `<path>\t<millis>` line per project; `%`, tab and newline in paths are percent-escaped. */
object RecentProjectsCodec {
    fun encode(list: List<RecentProject>): String =
        list.joinToString("\n") { esc(it.path) + "\t" + it.openedAt }

    fun decode(raw: String?): List<RecentProject> = raw.orEmpty().split('\n').mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 2 || f[0].isEmpty()) return@mapNotNull null
        val time = f[1].toLongOrNull() ?: return@mapNotNull null
        RecentProject(unesc(f[0]), time)
    }

    private fun esc(s: String) = s.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A")
    private fun unesc(s: String) = s.replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")
}

/** What the project is called: the folder name, or [privateLabel] for the app's own projects folder. */
fun projectName(root: File, privateRoot: File, privateLabel: String): String = when {
    root.path == privateRoot.path -> privateLabel
    root.name.isEmpty() -> root.path
    else -> root.name
}

/** True if [file] is [dir] or inside it (plain path comparison: pass resolved paths). */
internal fun isAtOrUnder(file: File, dir: File): Boolean {
    val d = dir.path.trimEnd(File.separatorChar)
    return file.path == dir.path || file.path == d || file.path.startsWith(d + File.separator)
}

/** Whether shared storage can be used, and how to ask for it. */
object StorageAccess {
    enum class Request {
        /** Android 11+: "All files access", granted on a Settings page. */
        AllFilesSettings,

        /** Android 7–10: the READ/WRITE_EXTERNAL_STORAGE runtime permission. */
        RuntimePermission,
    }

    fun requestFor(sdk: Int): Request = if (sdk >= 30) Request.AllFilesSettings else Request.RuntimePermission

    /** The signal that counts on [sdk]: all-files access on 11+, the write permission before. */
    fun granted(sdk: Int, allFilesAccess: Boolean, writePermission: Boolean): Boolean =
        if (requestFor(sdk) == Request.AllFilesSettings) allFilesAccess else writePermission

    /** False for folders the app can always use (its own private and app-specific folders). */
    fun needsPermission(dir: File, appFolders: List<File>): Boolean = appFolders.none { isAtOrUnder(dir, it) }
}

/** A top-level place of the folder browser: the phone's own shared storage or a memory card. */
data class StorageRoot(val dir: File, val kind: Kind) {
    enum class Kind { Internal, SdCard }
}

object StorageRoots {
    /**
     * The volume an app-specific folder (`<volume>/Android/data/<package>/files`, as returned by
     * `Context.getExternalFilesDirs`) lives on, or null for anything else.
     */
    fun volumeRootOf(appDir: File): File? {
        if (appDir.name != "files") return null
        val pkg = appDir.parentFile ?: return null
        val data = pkg.parentFile ?: return null
        val android = data.parentFile ?: return null
        if (data.name != "data" || android.name != "Android") return null
        return android.parentFile
    }

    /** The roots for `getExternalFilesDirs(null)`: the first volume is internal storage, the rest are cards. */
    fun fromAppDirs(appDirs: List<File?>): List<StorageRoot> =
        appDirs.mapNotNull { it?.let(::volumeRootOf) }
            .distinctBy { it.path }
            .mapIndexed { i, dir -> StorageRoot(dir, if (i == 0) StorageRoot.Kind.Internal else StorageRoot.Kind.SdCard) }

    /** The root [dir] is on (the deepest one holding it), or null. */
    fun rootFor(dir: File, roots: List<StorageRoot>): StorageRoot? =
        roots.filter { isAtOrUnder(dir, it.dir) }.maxByOrNull { it.dir.path.length }
}

/** One breadcrumb of the folder browser; the root's [label] is empty (the UI names the root). */
data class BrowseCrumb(val label: String, val dir: File)

object FolderListing {
    /** Children of [dir]: folders first, then by name ignoring case; dot-entries only if [showHidden]. */
    fun list(dir: File, showHidden: Boolean): List<FileEntry> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { showHidden || !it.name.startsWith(".") }
            .map { FileEntry(it, it.isDirectory) }
            .sortedWith(
                compareBy<FileEntry> { !it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.name },
            )
    }

    /** From [root] down to [dir]; just the root if [dir] is not under it. */
    fun crumbs(dir: File, root: File): List<BrowseCrumb> {
        if (!isAtOrUnder(dir, root)) return listOf(BrowseCrumb("", root))
        val out = ArrayDeque<BrowseCrumb>()
        var d: File? = dir
        while (d != null && d.path != root.path) {
            out.addFirst(BrowseCrumb(d.name, d))
            d = d.parentFile
        }
        out.addFirst(BrowseCrumb("", root))
        return out.toList()
    }

    /** The parent of [dir], or null at (or outside) [root]. */
    fun parentWithin(dir: File, root: File): File? {
        if (dir.path == root.path || !isAtOrUnder(dir, root)) return null
        return dir.parentFile
    }
}

/** Is a folder usable as the project, and which project to open at start. */
object ProjectCheck {
    enum class Problem { Missing, NotAFolder, NoPermission, Unreadable }

    /** Null when [dir] can be opened as a project (reads the disk). */
    fun problem(dir: File): Problem? = when {
        dir.isDirectory -> if (dir.list() == null) Problem.Unreadable else null
        dir.exists() -> Problem.NotAFolder
        else -> Problem.Missing
    }

    /** The project to start with; [failed] and [problem] say why the saved one could not be reopened. */
    data class Startup(val root: File, val problem: Problem? = null, val failed: File? = null)

    /**
     * [saved] is the last project's path (null: the app's own projects). A shared-storage project
     * needs [granted] storage access ([needsPermission]) and must still exist; otherwise the app's
     * own [privateRoot] opens and the reason is returned for a friendly message.
     */
    fun startup(saved: String?, privateRoot: File, needsPermission: (File) -> Boolean, granted: Boolean): Startup {
        if (saved.isNullOrEmpty()) return Startup(privateRoot)
        val dir = File(saved)
        if (dir.path == privateRoot.path) return Startup(privateRoot)
        if (needsPermission(dir) && !granted) return Startup(privateRoot, Problem.NoPermission, dir)
        val problem = try {
            problem(dir)
        } catch (e: SecurityException) {
            Problem.NoPermission
        } catch (e: IOException) {
            Problem.Unreadable
        }
        return if (problem == null) Startup(dir) else Startup(privateRoot, problem, dir)
    }
}
