package dev.skyloft.skypython.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SymbolKeysDraftTest {

    @Test
    fun anUnsavedEditIsStillThereToSave() {
        val d = SymbolKeysDraft("( )")
        assertNull(d.toSave())
        d.edit("( ) [")
        // Leaving the screen before the pause ends saves this, instead of losing it.
        assertEquals("( ) [", d.toSave())
        d.saving("( ) [")
        assertNull(d.toSave())
    }

    @Test
    fun theFieldsOwnSaveComingBackKeepsWhatIsBeingTyped() {
        val d = SymbolKeysDraft("(")
        d.edit("( [")
        d.saving("( [")
        d.edit("( [ ") // a space typed after the save went out
        d.storeChanged(normalizeSymbolKeys("( ["))
        assertEquals("( [ ", d.text)
    }

    @Test
    fun aChangeFromElsewhereIsShown() {
        val d = SymbolKeysDraft("(")
        d.storeChanged("{ }")
        assertEquals("{ }", d.text)
        assertNull(d.toSave())
    }
}
