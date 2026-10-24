package dev.skyloft.skypython.runtime

/** Output of a [PythonProcess] run. [Exited] or [Failed] is always the last event. */
sealed interface ConsoleEvent {
    data class Stdout(val text: String) : ConsoleEvent
    data class Stderr(val text: String) : ConsoleEvent
    data class Exited(val code: Int) : ConsoleEvent
    /** The process could not be started. */
    data class Failed(val message: String) : ConsoleEvent
}
