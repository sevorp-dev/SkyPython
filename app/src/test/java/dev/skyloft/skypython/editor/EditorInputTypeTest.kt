package dev.skyloft.skypython.editor

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInputTypeTest {
    private fun has(type: Int, flag: Int) = type and flag == flag

    @Test
    fun codeInputTypeTurnsSuggestionsAndAutocorrectOff() {
        val t = editorInputType(keyboardSuggestions = false)
        assertEquals(InputType.TYPE_CLASS_TEXT, t and InputType.TYPE_MASK_CLASS)
        assertTrue(has(t, InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(has(t, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        // Keyboards that ignore NO_SUGGESTIONS still never autocorrect a password field.
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, t and InputType.TYPE_MASK_VARIATION)
        assertEquals(0, t and (InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
    }

    @Test
    fun suggestionsOnIsPlainMultiLineTextWithoutAutoCapitalization() {
        val t = editorInputType(keyboardSuggestions = true)
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, t)
    }
}
