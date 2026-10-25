package dev.skyloft.skypython.files

import dev.skyloft.skypython.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** Why a write failed, in words a user understands; Android's own message is kept as the detail. */
class IoErrorsTest {

    @Test fun `common write failures are recognised from the errno in the message`() {
        assertEquals(IoProblem.NoSpace, ioProblemOf(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(IoProblem.Denied, ioProblemOf(FileNotFoundException("/x/out.txt: open failed: EACCES (Permission denied)")))
        assertEquals(IoProblem.ReadOnly, ioProblemOf(IOException("open failed: EROFS (Read-only file system)")))
        assertEquals(IoProblem.Other, ioProblemOf(IOException("Broken pipe")))
        // Wrapped: the cause counts.
        assertEquals(IoProblem.NoSpace, ioProblemOf(RuntimeException(IOException("ENOSPC"))))
    }

    @Test fun `the user sees the reason in their language and the detail after it`() {
        val text: (Int) -> String = { if (it == R.string.io_reason_no_space) "мало места" else "?" }
        val e = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals("мало места (write failed: ENOSPC (No space left on device))", describeIoError(e, text))
    }
}
