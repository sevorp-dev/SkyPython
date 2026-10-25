package dev.skyloft.skypython.terminal

import dev.skyloft.skypython.files.FileKind
import java.io.File

/**
 * The pictures a script made: the images in its folder are listed before ▶ Run and again after
 * it ends; new ones and ones written over are offered in the finished bar.
 */
object RunImages {
    /** Folders below the script's that are looked into (out/plot.png, but not deeper). */
    const val MAX_DEPTH = 2

    /** At most this many files are looked at, so a huge folder never stalls the run. */
    private const val MAX_FILES = 5000

    /** One image as it was: when it was last written and how big it was. */
    data class Stamp(val modified: Long, val length: Long)

    /** The images under [dir] (to [MAX_DEPTH] folders down, skipping hidden and cache folders). */
    fun snapshot(dir: File): Map<File, Stamp> {
        val out = HashMap<File, Stamp>()
        var seen = 0
        fun walk(folder: File, depth: Int) {
            val children = folder.listFiles() ?: return
            for (child in children) {
                if (++seen > MAX_FILES) return
                val name = child.name
                if (child.isDirectory) {
                    if (depth < MAX_DEPTH && !name.startsWith(".") && name != "__pycache__") walk(child, depth + 1)
                } else if (FileKind.byName(name) == FileKind.Image) {
                    out[child] = Stamp(child.lastModified(), child.length())
                }
            }
        }
        walk(dir, 0)
        return out
    }

    /** The images in [after] that are new or changed since [before], newest first, at most [limit]. */
    fun changed(before: Map<File, Stamp>, after: Map<File, Stamp>, limit: Int = 4): List<File> =
        after.entries
            .filter { (file, stamp) -> before[file] != stamp }
            .sortedWith(compareByDescending<Map.Entry<File, Stamp>> { it.value.modified }.thenBy { it.key.name })
            .take(limit)
            .map { it.key }
}
