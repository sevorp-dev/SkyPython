package dev.skyloft.skypython.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunPlotsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val a = File("/data/user/0/dev.skyloft.skypython/cache/plots/1-a/1/figure-1.png")
    private val b = File("/data/user/0/dev.skyloft.skypython/cache/plots/1-a/1/figure-2.png")
    private val c = File("/data/user/0/dev.skyloft.skypython/cache/plots/1-a/2/figure-1.png")

    @Test
    fun parsesThePathsTheBackendSends() {
        assertEquals(listOf(a), RunPlots.parse("plot;" + a.invariantSeparatorsPath))
        assertEquals(listOf(a, b), RunPlots.parse("plot;${a.invariantSeparatorsPath};${b.invariantSeparatorsPath}"))
        // urllib.parse.quote: spaces, ';', non-ASCII and '+'.
        assertEquals(
            listOf(File("/sd/my plots;x/grafik é+1.png")),
            RunPlots.parse("plot;/sd/my%20plots%3Bx/grafik%20%C3%A9%2B1.png"),
        )
        assertEquals(listOf(File("/sd/a+b.png")), RunPlots.parse("plot;/sd/a+b.png"))
    }

    @Test
    fun otherCommandsAndBadPathsAreIgnored() {
        assertNull(RunPlots.parse("open;/x/a.png"))
        assertNull(RunPlots.parse(""))
        assertEquals(emptyList<File>(), RunPlots.parse("plot"))
        assertEquals(emptyList<File>(), RunPlots.parse("plot;"))
        // Relative, not a picture, a broken escape.
        assertEquals(emptyList<File>(), RunPlots.parse("plot;figure-1.png;/x/script.py;/x/%zz.png"))
        assertEquals(listOf(File("/x/ok.jpg")), RunPlots.parse("plot;/etc/passwd;/x/ok.jpg"))
    }

    @Test
    fun aShowOpensTheViewerAtItsFirstFigure() {
        val first = RunPlots.received(TerminalState(), "plot;${a.invariantSeparatorsPath};${b.invariantSeparatorsPath}")!!
        assertEquals(listOf(a, b), first.plots)
        assertEquals(0, first.showPlot)
        // The viewer opened; the next show() adds to the list and moves there.
        val second = RunPlots.received(first.copy(showPlot = null), "plot;" + c.invariantSeparatorsPath)!!
        assertEquals(listOf(a, b, c), second.plots)
        assertEquals(2, second.showPlot)
        // The same file again is not listed twice, but is shown.
        val again = RunPlots.received(second.copy(showPlot = null), "plot;" + a.invariantSeparatorsPath)!!
        assertEquals(listOf(a, b, c), again.plots)
        assertEquals(0, again.showPlot)
    }

    @Test
    fun notAPlotLeavesTheStateAlone() {
        val state = TerminalState(plots = listOf(a), showPlot = null)
        assertNull(RunPlots.received(state, "title;x"))
        assertNull(RunPlots.received(state, "plot;relative.png"))
    }

    @Test
    fun onlyTheNewestPlotsAreKept() {
        var plots = emptyList<File>()
        repeat(RunPlots.MAX_PLOTS + 5) { plots = RunPlots.add(plots, listOf(File("/p/$it/figure-1.png"))) }
        assertEquals(RunPlots.MAX_PLOTS, plots.size)
        assertEquals(File("/p/5/figure-1.png"), plots.first())
        assertEquals(File("/p/${RunPlots.MAX_PLOTS + 4}/figure-1.png"), plots.last())
    }

    @Test
    fun labels() {
        assertEquals("1", RunPlots.label(a))
        assertEquals("My_plot", RunPlots.label(File("/x/figure-My_plot.png")))
        assertEquals("chart", RunPlots.label(File("/x/chart.png")))
    }

    @Test
    fun saveCopyKeepsEarlierSaves() {
        val plot = File(tmp.newFolder("plots", "1"), "figure-1.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val project = File(tmp.root, "project")
        val first = RunPlots.saveCopy(plot, project)
        assertEquals(File(project, "figure-1.png"), first)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        val second = RunPlots.saveCopy(plot, project)
        assertEquals(File(project, "figure-1-2.png"), second)
        assertEquals(setOf("figure-1.png", "figure-1-2.png"), project.list()!!.toSet()) // no temp files left
    }
}
