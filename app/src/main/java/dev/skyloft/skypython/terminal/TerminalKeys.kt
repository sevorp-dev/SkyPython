package dev.skyloft.skypython.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler

/** The keys a phone keyboard lacks, shown in a row above it. */
enum class ExtraKey(val label: String, val description: String) {
    Esc("ESC", "Escape"),
    Tab("TAB", "Tab"),
    Ctrl("CTRL", "Control"),
    Alt("ALT", "Alt"),
    Dash("-", "Minus"),
    Slash("/", "Slash"),
    Pipe("|", "Pipe"),
    Left("←", "Left"),
    Up("↑", "Up"),
    Down("↓", "Down"),
    Right("→", "Right"),
    ;

    val isModifier: Boolean get() = this == Ctrl || this == Alt

    companion object {
        val ROW: List<ExtraKey> = entries
    }
}

/**
 * Ctrl / Alt from the key row: a tap arms it for the next key (from the row or the keyboard),
 * a second tap releases it.
 */
data class Modifiers(val ctrl: Boolean = false, val alt: Boolean = false) {
    fun toggle(key: ExtraKey): Modifiers = when (key) {
        ExtraKey.Ctrl -> copy(ctrl = !ctrl)
        ExtraKey.Alt -> copy(alt = !alt)
        else -> this
    }

    /** Both are used up by one key. */
    fun afterKey(): Modifiers = Modifiers()
}

/** What each key sends to the program (xterm conventions, same as the terminal view's keyboard). */
object TerminalKeys {

    /** Ctrl+C: the PTY turns it into SIGINT for the foreground program. */
    const val CTRL_C = "\u0003"

    /**
     * The bytes for [key] with [mods]; null for the modifier keys themselves.
     * @param cursorApp the program asked for application cursor keys (`ESC O A` instead of `ESC [ A`).
     */
    fun sequence(key: ExtraKey, mods: Modifiers, cursorApp: Boolean, keypadApp: Boolean = false): String? {
        // Alt is "meta sends escape": ESC before the key's own bytes.
        val altPrefix = if (mods.alt) "\u001b" else ""
        val char = when (key) {
            ExtraKey.Ctrl, ExtraKey.Alt -> return null
            ExtraKey.Esc -> return altPrefix + "\u001b"
            ExtraKey.Tab -> return altPrefix + "\t"
            ExtraKey.Dash -> '-'
            ExtraKey.Slash -> '/'
            ExtraKey.Pipe -> '|'
            else -> null
        }
        if (char != null) {
            val cp = if (mods.ctrl) controlCode(char.code) else char.code
            return altPrefix + String(Character.toChars(cp))
        }
        // Arrows: the emulator's own key table (modifier parameters, application cursor mode).
        val keyCode = when (key) {
            ExtraKey.Left -> KeyEvent.KEYCODE_DPAD_LEFT
            ExtraKey.Up -> KeyEvent.KEYCODE_DPAD_UP
            ExtraKey.Down -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        var keyMod = 0
        if (mods.ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (mods.alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        return KeyHandler.getCode(keyCode, keyMod, cursorApp, keypadApp)
    }

    /**
     * Ctrl+[codePoint] as a C0 control code, the same table the terminal view uses for typed
     * keys: letters → 1..26, space → 0, `[ \ ] ^ _ /` → 27..31. Others are returned unchanged.
     */
    fun controlCode(codePoint: Int): Int = when (codePoint) {
        in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        ' '.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code -> 31
        '8'.code -> 127
        else -> codePoint
    }
}
