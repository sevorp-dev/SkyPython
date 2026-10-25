package dev.skyloft.skypython.pip

import dev.skyloft.skypython.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** pip failures as text in the app's language, the technical detail after it. */
class PipErrorsTest {

    /** Stands in for the app's strings: "<resource>[args]". */
    private val text: (Int, Array<out Any>) -> String = { id, args ->
        val name = when (id) {
            R.string.pip_error_exit -> "exit"
            R.string.pip_error_unfinished -> "unfinished"
            R.string.pip_error_start -> "start"
            R.string.pip_error_other -> "other"
            else -> "?$id"
        }
        name + args.toList()
    }

    @Test fun `a non-zero exit names the command and its code, then pip's last lines`() {
        val e = PipFailedException(1, what = "pip", output = "ERROR: No matching distribution found for nosuchpkg")
        assertEquals("exit[pip, 1]\nERROR: No matching distribution found for nosuchpkg", describePipError(e, text))
    }

    @Test fun `no output means just the sentence`() {
        assertEquals("exit[ensurepip, 2]", describePipError(PipFailedException(2, what = "ensurepip"), text))
        assertEquals("unfinished[pip]", describePipError(PipFailedException(null, what = "pip"), text))
    }

    @Test fun `python that could not start`() {
        val e = PipFailedException(null, what = "pip", startFailure = "Cannot start Python: ENOENT")
        assertEquals("start[Cannot start Python: ENOENT]", describePipError(e, text))
    }

    @Test fun `anything else keeps its message as the detail`() {
        assertEquals("other[disk full]", describePipError(IOException("disk full"), text))
        assertEquals("other[IllegalStateException]", describePipError(IllegalStateException(), text))
    }
}
