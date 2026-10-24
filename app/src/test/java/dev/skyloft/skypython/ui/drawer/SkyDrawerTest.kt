package dev.skyloft.skypython.ui.drawer

import dev.skyloft.skypython.editor.CursorPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkyDrawerTest {

    @Test fun `header shows 1-based line of total and 0-based offset`() {
        val header = drawerHeader("extract.py", CursorPosition(line = 0, column = 0, lineCount = 73))
        assertEquals("extract.py", header.title)
        assertEquals("Line: 1/73", header.line)
        assertEquals("Line offset: 0", header.offset)
    }

    @Test fun `header follows the cursor`() {
        val header = drawerHeader("main.py", CursorPosition(line = 9, column = 4, lineCount = 12))
        assertEquals("Line: 10/12", header.line)
        assertEquals("Line offset: 4", header.offset)
    }

    @Test fun `empty document still counts one line`() {
        val header = drawerHeader("new.py", CursorPosition(line = 0, column = 0, lineCount = 0))
        assertEquals("Line: 1/1", header.line)
    }

    @Test fun `no open file shows a placeholder and no cursor lines`() {
        val header = drawerHeader(null, CursorPosition())
        assertEquals("No file open", header.title)
        assertNull(header.line)
        assertNull(header.offset)
    }

    @Test fun `drawer sections follow the spec order`() {
        assertEquals(
            listOf(
                "Run" to listOf(DrawerAction.Interpreter, DrawerAction.Terminal),
                "Tools" to listOf(DrawerAction.Pip, DrawerAction.Share, DrawerAction.Samples),
                "Settings" to listOf(DrawerAction.Settings, DrawerAction.About),
            ),
            DRAWER_SECTIONS.map { it.title to it.actions },
        )
    }
}
