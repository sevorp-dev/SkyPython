package dev.skyloft.skypython.samples

import android.content.Context
import dev.skyloft.skypython.files.isValidName
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Folder inside `assets/` holding the bundled sample scripts. */
const val SAMPLES_ASSET_DIR = "samples"

/** A bundled sample: its asset file [name] (e.g. `guess_number.py`) and a human [title]. */
data class Sample(val name: String, val title: String)

/**
 * Samples whose title or file name contain every word of [query] (case-insensitive, any order);
 * a blank query keeps them all.
 */
fun filterSamples(samples: List<Sample>, query: String): List<Sample> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return samples
    return samples.filter { s ->
        val text = (s.title + " " + s.name).lowercase()
        words.all { it in text }
    }
}

/**
 * Title of a sample: the text of its first line if that line is a `#` comment,
 * otherwise the file name without extension.
 */
fun sampleTitle(source: String, fileName: String): String {
    val first = source.removePrefix(Char(0xFEFF).toString()).lineSequence().firstOrNull()?.trim().orEmpty()
    val text = if (first.startsWith("#")) first.removePrefix("#").trim() else ""
    return text.ifEmpty { fileName.substringBeforeLast('.') }
}

/** Python files among [names], sorted by name, each titled via [readText]. */
fun listSamples(names: List<String>, readText: (String) -> String): List<Sample> =
    names.filter { it.endsWith(".py") }.sorted().map { Sample(it, sampleTitle(readText(it), it)) }

/**
 * Copies sample [name] to `projectsRoot/samples/<name>` and returns it.
 * An existing copy (possibly edited by the user) is never overwritten; it is returned as is.
 */
fun copySample(name: String, projectsRoot: File, open: (String) -> InputStream): File {
    require(isValidName(name)) { "Invalid sample name: $name" }
    val dir = File(projectsRoot, "samples")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
    val target = File(dir, name)
    if (target.exists()) return target
    // Write to a temp file first so a failed read never leaves an empty "sample" behind.
    val tmp = File(dir, ".$name.tmp")
    try {
        open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) {
            if (target.exists()) return target // lost a race with another copy; keep that one
            throw IOException("Could not create ${target.path}")
        }
    } finally {
        tmp.delete()
    }
    return target
}

/** Bundled samples from `assets/samples/`. */
fun Context.bundledSamples(): List<Sample> {
    val names = assets.list(SAMPLES_ASSET_DIR)?.toList().orEmpty()
    return listSamples(names) { name ->
        assets.open("$SAMPLES_ASSET_DIR/$name").bufferedReader().use { it.readLine().orEmpty() }
    }
}

/** [copySample] reading from the app's `assets/samples/`. */
fun Context.copyBundledSample(name: String, projectsRoot: File): File =
    copySample(name, projectsRoot) { assets.open("$SAMPLES_ASSET_DIR/$it") }
