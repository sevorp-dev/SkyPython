package dev.skyloft.skypython.files

import dev.skyloft.skypython.R

/** Why reading or writing a file failed, as far as the user is concerned. */
enum class IoProblem { NoSpace, Denied, ReadOnly, Other }

/** The problem behind [e] (or its causes), from the errno Android puts in the message. */
fun ioProblemOf(e: Throwable): IoProblem {
    var t: Throwable? = e
    while (t != null) {
        val m = t.message.orEmpty()
        when {
            "ENOSPC" in m || "EDQUOT" in m -> return IoProblem.NoSpace
            "EACCES" in m || "EPERM" in m -> return IoProblem.Denied
            "EROFS" in m -> return IoProblem.ReadOnly
        }
        t = t.cause?.takeIf { it !== t }
    }
    return IoProblem.Other
}

/** "<reason in the app's language> (<Android's message>)"; [text] reads a string resource. */
fun describeIoError(e: Throwable, text: (Int) -> String): String {
    val reason = text(
        when (ioProblemOf(e)) {
            IoProblem.NoSpace -> R.string.io_reason_no_space
            IoProblem.Denied -> R.string.io_reason_denied
            IoProblem.ReadOnly -> R.string.io_reason_read_only
            IoProblem.Other -> R.string.io_reason_other
        },
    )
    return "$reason (${e.message ?: e.javaClass.simpleName})"
}
