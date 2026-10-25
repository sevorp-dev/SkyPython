package dev.skyloft.skypython.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class PinchFontSizeTest {
    @Test
    fun pinchedSizeIsRoundedToWholeSpAndClamped() {
        assertEquals(18, pinchFontSp(textSizePx = 18.4f * 2.75f, spScale = 2.75f))
        assertEquals(19, pinchFontSp(textSizePx = 18.6f * 2f, spScale = 2f))
        assertEquals(12, pinchFontSp(textSizePx = 8f * 2f, spScale = 2f))
        assertEquals(24, pinchFontSp(textSizePx = 40f * 2f, spScale = 2f))
    }
}
