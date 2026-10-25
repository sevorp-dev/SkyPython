package dev.skyloft.skypython.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.skyloft.skypython.ui.theme.CodeFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.lang.SignatureHints
import io.github.rosemoe.sora.widget.CodeEditor

/** Pause in typing before the hint is asked for; the next keystroke cancels the request. */
internal const val HINT_DELAY_MS = 250L
/** How far back to look for the call's `(` (a call spanning more lines gets no hint). */
internal const val HINT_LOOKBACK_LINES = 8

/** The cursor's row in the editor view, in px: [x] of the cursor, [top] / [bottom] of the row. */
data class HintAnchor(val x: Float, val top: Float, val bottom: Float)

data class HintState(val hint: SignatureHints.Hint, val anchor: HintAnchor)

/** Where the cursor at [line] / [column] is drawn in [v] (view coordinates, scroll applied). */
internal fun hintAnchor(v: CodeEditor, line: Int, column: Int): HintAnchor {
    // getCharOffsetY is the bottom of the character's row.
    val bottom = v.getCharOffsetY(line, column)
    return HintAnchor(v.getCharOffsetX(line, column), bottom - v.rowHeight, bottom)
}

/**
 * The parameter hint: `print(*values, sep=' ', …)` with the parameter being typed in bold,
 * floating just above the cursor's line (below it near the top edge). Drawn over the editor
 * without taking touches: no Surface, which would swallow taps meant for the code.
 */
@Composable
internal fun SignatureHintBubble(state: HintState) {
    val h = state.hint
    val accent = MaterialTheme.colorScheme.primary
    val text = remember(h, accent) {
        buildAnnotatedString {
            append(h.text)
            if (h.boldEnd > h.boldStart) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent), h.boldStart, h.boldEnd)
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = CodeFont,
        fontSize = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .layout { measurable, constraints ->
                val margin = 8.dp.roundToPx()
                val gap = 4.dp.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0),
                    ),
                )
                val a = state.anchor
                val maxX = (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin)
                val x = (a.x.toInt() - 16.dp.roundToPx()).coerceIn(margin, maxX)
                val above = a.top.toInt() - placeable.height - gap
                val y = if (above >= 0) above else a.bottom.toInt() + gap
                layout(placeable.width, placeable.height) { placeable.place(x, y) }
            }
            .testTag("signature_hint")
            .shadow(4.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
