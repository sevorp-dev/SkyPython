package dev.skyloft.skypython.editor

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorShortcutsTest {
    @Test
    fun ctrlShortcuts() {
        assertEquals(EditorShortcut.Save, editorShortcut(KeyEvent.KEYCODE_S, ctrl = true, shift = false))
        assertEquals(EditorShortcut.Find, editorShortcut(KeyEvent.KEYCODE_F, ctrl = true, shift = false))
        assertEquals(EditorShortcut.Replace, editorShortcut(KeyEvent.KEYCODE_H, ctrl = true, shift = false))
        assertEquals(EditorShortcut.ToggleComment, editorShortcut(KeyEvent.KEYCODE_SLASH, ctrl = true, shift = false))
        assertEquals(EditorShortcut.Run, editorShortcut(KeyEvent.KEYCODE_R, ctrl = true, shift = false))
        assertEquals(EditorShortcut.GoToLine, editorShortcut(KeyEvent.KEYCODE_G, ctrl = true, shift = false))
        assertEquals(EditorShortcut.Redo, editorShortcut(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))
    }

    @Test
    fun f5RunsWithoutModifiers() {
        assertEquals(EditorShortcut.Run, editorShortcut(KeyEvent.KEYCODE_F5, ctrl = false, shift = false))
    }

    @Test
    fun plainLettersAndEditorOwnedKeysAreNotShortcuts() {
        assertNull(editorShortcut(KeyEvent.KEYCODE_S, ctrl = false, shift = false))
        // Ctrl+Z / Ctrl+Y / Ctrl+C … are handled by the editor itself.
        assertNull(editorShortcut(KeyEvent.KEYCODE_Z, ctrl = true, shift = false))
        assertNull(editorShortcut(KeyEvent.KEYCODE_Y, ctrl = true, shift = false))
        assertNull(editorShortcut(KeyEvent.KEYCODE_C, ctrl = true, shift = false))
    }

    @Test
    fun goToDefinitionIsCtrlBOrF12() {
        assertEquals(EditorShortcut.GoToDefinition, editorShortcut(KeyEvent.KEYCODE_B, ctrl = true, shift = false))
        assertEquals(EditorShortcut.GoToDefinition, editorShortcut(KeyEvent.KEYCODE_F12, ctrl = false, shift = false))
        assertEquals(EditorShortcut.Outline, editorShortcut(KeyEvent.KEYCODE_O, ctrl = true, shift = true))
        assertNull(editorShortcut(KeyEvent.KEYCODE_B, ctrl = false, shift = false))
    }
}
