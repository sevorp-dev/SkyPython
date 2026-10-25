package dev.skyloft.skypython.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt

/**
 * How far up from the window bottom the current screen's bottom controls reach (the editor's
 * status line and symbol bar, the terminal's key row), so the app-wide snackbar (problem banner)
 * is shown above them instead of over them. Screens mark those controls with [keepsSnackbarsAbove].
 */
@Stable
class SnackbarClearance {
    private val tops = mutableStateMapOf<Any, Int>()

    /** Distance (px) from the window bottom to the top of the highest marked control; 0 if none. */
    val px: Int get() = tops.values.maxOrNull() ?: 0

    fun set(owner: Any, px: Int) {
        if (tops[owner] != px) tops[owner] = px
    }

    fun remove(owner: Any) {
        tops.remove(owner)
    }

    companion object {
        /**
         * Extra bottom padding (px) for a snackbar host whose own bottom edge is [hostGapPx] above
         * the window bottom (the app's bottom bar is under it), to clear [clearancePx].
         */
        fun lift(clearancePx: Int, hostGapPx: Int): Int = (clearancePx - hostGapPx).coerceAtLeast(0)
    }
}

val LocalSnackbarClearance = staticCompositionLocalOf { SnackbarClearance() }

/** The app-wide snackbar keeps above this (see [SnackbarClearance]). */
fun Modifier.keepsSnackbarsAbove(): Modifier = composed {
    val clearance = LocalSnackbarClearance.current
    val owner = remember { Any() }
    DisposableEffect(clearance) { onDispose { clearance.remove(owner) } }
    onGloballyPositioned { c ->
        val root = c.findRootCoordinates()
        clearance.set(owner, (root.size.height - c.positionInRoot().y).roundToInt().coerceAtLeast(0))
    }
}
