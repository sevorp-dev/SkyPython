package dev.skyloft.skypython.editor

import android.view.KeyEvent

/** Hardware-keyboard commands handled by the editor screen (on top of the editor's own keys). */
enum class EditorShortcut { Save, Find, Replace, GoToLine, ToggleComment, Run, Redo, GoToDefinition, Outline }

/**
 * Ctrl+S save, Ctrl+F find, Ctrl+H replace, Ctrl+G go to line, Ctrl+/ comment, Ctrl+R or F5 run,
 * Ctrl+Shift+Z redo, Ctrl+B or F12 go to definition, Ctrl+Shift+O outline.
 * Ctrl+Z / Ctrl+Y / Ctrl+C / Ctrl+V / Ctrl+X / Ctrl+A are the editor's own.
 */
fun editorShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean): EditorShortcut? {
    if (keyCode == KeyEvent.KEYCODE_F5 && !ctrl) return EditorShortcut.Run
    if (keyCode == KeyEvent.KEYCODE_F12 && !ctrl) return EditorShortcut.GoToDefinition
    if (!ctrl) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_S -> EditorShortcut.Save
        KeyEvent.KEYCODE_F -> EditorShortcut.Find
        KeyEvent.KEYCODE_H -> EditorShortcut.Replace
        KeyEvent.KEYCODE_G -> EditorShortcut.GoToLine
        KeyEvent.KEYCODE_SLASH -> EditorShortcut.ToggleComment
        KeyEvent.KEYCODE_R -> EditorShortcut.Run
        KeyEvent.KEYCODE_B -> EditorShortcut.GoToDefinition
        KeyEvent.KEYCODE_O -> if (shift) EditorShortcut.Outline else null
        KeyEvent.KEYCODE_Z -> if (shift) EditorShortcut.Redo else null
        else -> null
    }
}
