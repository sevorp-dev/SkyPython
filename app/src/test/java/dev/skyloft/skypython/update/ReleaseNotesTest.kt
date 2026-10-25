package dev.skyloft.skypython.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun headingsBulletsAndParagraphs() {
        val blocks = ReleaseNotes.parse(
            """
            # SkyPython 0.0.6

            A **faster** editor and [more](https://example.com) samples.
            Second line of the same paragraph.

            ## What's new
            - Open a project from storage
            * `pip` works offline
              continued bullet

            1. First
            2. Second
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                NoteBlock.Heading(1, "SkyPython 0.0.6"),
                NoteBlock.Paragraph("A faster editor and more samples. Second line of the same paragraph."),
                NoteBlock.Heading(2, "What's new"),
                NoteBlock.Bullet("Open a project from storage"),
                NoteBlock.Bullet("pip works offline continued bullet"),
                NoteBlock.Bullet("First", marker = "1."),
                NoteBlock.Bullet("Second", marker = "2."),
            ),
            blocks,
        )
    }

    @Test
    fun imagesHtmlAndRulesAreDropped() {
        val blocks = ReleaseNotes.parse(
            "![shot](docs/a.png)\n<p align=center>x</p>\n---\nText _here_ and __there__.\n",
        )
        assertEquals(listOf(NoteBlock.Paragraph("Text here and there.")), blocks)
    }

    @Test
    fun fencedCodeIsKeptVerbatim() {
        val blocks = ReleaseNotes.parse("Try:\n\n```python\nimport numpy as np\nprint(np.arange(3))  # **not bold**\n```\nDone.\n")
        assertEquals(
            listOf(
                NoteBlock.Paragraph("Try:"),
                NoteBlock.Code("import numpy as np\nprint(np.arange(3))  # **not bold**"),
                NoteBlock.Paragraph("Done."),
            ),
            blocks,
        )
    }

    @Test
    fun emptyNotes() {
        assertEquals(emptyList<NoteBlock>(), ReleaseNotes.parse("\n  \n"))
    }
}
