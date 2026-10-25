package dev.skyloft.skypython.terminal

/** What the terminal screen shows under its top bar. */
enum class TerminalBody {
    InstallError,
    StartError,
    Terminal,

    /** Python is being unpacked (first run, or after an update). */
    Installing,

    /** The program is being started (a moment). */
    Starting,

    /** ▶ Run with no script (the process was restarted since): nothing to show, back to the editor. */
    NoScript;

    companion object {
        fun of(state: TerminalState, kind: TerminalKind): TerminalBody = when {
            state.installError != null -> InstallError
            state.startError != null -> StartError
            state.session != null -> Terminal
            state.installing -> Installing
            kind == TerminalKind.Run && state.script == null -> NoScript
            else -> Starting
        }

        /**
         * The interpreter and the shell start as soon as their screen is shown, unless something
         * is already there, starting or failed: a screen brought back after the process died has
         * a fresh view model that never saw the click that opened it. A script never reruns by
         * itself.
         */
        fun startsWhenShown(state: TerminalState, kind: TerminalKind, starting: Boolean): Boolean =
            kind != TerminalKind.Run && !starting && state.session == null &&
                state.installError == null && state.startError == null
    }
}
