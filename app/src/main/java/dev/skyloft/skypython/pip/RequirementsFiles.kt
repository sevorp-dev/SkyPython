package dev.skyloft.skypython.pip

import java.io.File

/** Finds files `pip install -r` could use: `.txt` files in the projects folder. */
object RequirementsFiles {

    private val SKIPPED_DIRS = setOf("__pycache__", "venv", "env", "site-packages", "node_modules", "build", "dist")

    /**
     * `.txt` files under [root] at most [maxDepth] folders deep, without hidden and tool
     * folders. `requirements*.txt` come first, then shallower files, then by path.
     */
    fun find(root: File, maxDepth: Int = 4, limit: Int = 100): List<File> {
        if (!root.isDirectory) return emptyList()
        val found = mutableListOf<Pair<File, Int>>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (depth < maxDepth && child.name !in SKIPPED_DIRS) walk(child, depth + 1)
                } else if (isTxt(child)) {
                    found += child to depth
                }
            }
        }
        walk(root, 0)
        return found
            .sortedWith(
                compareBy<Pair<File, Int>>(
                    { !it.first.name.startsWith("requirements", ignoreCase = true) },
                    { it.second },
                    { it.first.relativeTo(root).invariantSeparatorsPath.lowercase() },
                ),
            )
            .take(limit)
            .map { it.first }
    }

    /** An existing `.txt` file, e.g. the one open in the editor. */
    fun isCandidate(file: File): Boolean = file.isFile && isTxt(file)

    private fun isTxt(file: File) = file.name.endsWith(".txt", ignoreCase = true)
}
