package dev.skyloft.skypython.samples

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

class SamplesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sources = mapOf("hello.py" to "# Say hello\nprint('hi')\n")
    private val read: (String) -> InputStream = { name ->
        (sources[name] ?: throw FileNotFoundException(name)).byteInputStream()
    }

    @Test
    fun titleComesFromFirstCommentLine() {
        assertEquals("Hello world with input", sampleTitle("# Hello world with input\nprint(1)", "x.py"))
        assertEquals("Tight", sampleTitle("#Tight", "x.py"))
        assertEquals("Spaces", sampleTitle("  #   Spaces   \n", "x.py"))
        assertEquals("BOM", sampleTitle("﻿# BOM\n", "x.py"))
        assertEquals("CRLF", sampleTitle("# CRLF\r\nprint()", "x.py"))
    }

    @Test
    fun titleFallsBackToFileNameWithoutExtension() {
        assertEquals("guess_number", sampleTitle("print(1)\n", "guess_number.py"))
        assertEquals("empty", sampleTitle("", "empty.py"))
        assertEquals("blank", sampleTitle("#   \n", "blank.py"))
    }

    @Test
    fun listSamplesKeepsOnlyPythonFilesSorted() {
        val list = listSamples(listOf("b.py", "notes.txt", "a.py")) { "# Title ${it.first()}" }
        assertEquals(listOf(Sample("a.py", "Title a"), Sample("b.py", "Title b")), list)
    }

    @Test
    fun copySampleCreatesFileUnderProjectsSamples() {
        val root = tmp.newFolder("projects")
        val copy = copySample("hello.py", root, read)
        assertEquals(File(File(root, "samples"), "hello.py").canonicalFile, copy.canonicalFile)
        assertEquals(sources["hello.py"], copy.readText())
    }

    @Test
    fun copySampleNeverOverwritesExistingCopy() {
        val root = tmp.newFolder("projects")
        val first = copySample("hello.py", root, read)
        first.writeText("# my edits\n")
        val second = copySample("hello.py", root, read)
        assertEquals(first.canonicalFile, second.canonicalFile)
        assertEquals("# my edits\n", second.readText())
    }

    @Test
    fun copySampleRejectsPathTraversal() {
        val root = tmp.newFolder("projects")
        val result = runCatching { copySample("../evil.py", root, read) }
        assertTrue(result.isFailure)
        assertFalse(File(root, "evil.py").exists())
    }

    @Test
    fun failedReadLeavesNoEmptyFile() {
        val root = tmp.newFolder("projects")
        assertTrue(runCatching { copySample("missing.py", root, read) }.isFailure)
        assertFalse(File(File(root, "samples"), "missing.py").exists())
    }

    @Test
    fun filterMatchesEveryWordInTitleOrName() {
        val all = listOf(
            Sample("01_hello.py", "Hello world: print text and ask for your name"),
            Sample("02_loops_and_lists.py", "Loops and lists: for, while, ranges"),
            Sample("06_json.py", "JSON: turn Python data into JSON text"),
        )
        assertEquals(all, filterSamples(all, "   "))
        assertEquals(listOf(all[1]), filterSamples(all, "LOOPS"))
        assertEquals(listOf(all[1]), filterSamples(all, "lists for"))
        assertEquals(listOf(all[2]), filterSamples(all, "06_json"))
        assertEquals(emptyList<Sample>(), filterSamples(all, "hello json"))
    }

    /** Unit tests run with cwd = app/. */
    @Test
    fun bundledSamplesAllHaveCommentTitles() {
        val files = File("src/main/assets/samples").listFiles { f -> f.name.endsWith(".py") }.orEmpty()
        assertTrue("expected 6..12 samples, got ${files.size}", files.size in 6..12)
        for (f in files) {
            assertTrue("${f.name} must start with a comment", f.readLines().first().startsWith("# "))
            assertFalse(f.name, sampleTitle(f.readText(), f.name) == f.nameWithoutExtension)
        }
    }
}
