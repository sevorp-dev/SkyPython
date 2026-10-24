package dev.skyloft.skypython.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import dev.skyloft.skypython.ui.theme.BarBlue
import dev.skyloft.skypython.ui.theme.RunAmber

/** Label shown on the key → text it inserts. Tab inserts 4 spaces. */
val SYMBOL_KEYS: List<Pair<String, String>> =
    listOf("Tab" to "    ") +
        listOf(":", ";", "'", "\"", "#", "(", ")", "[", "]", "{", "}", "=", ".", ",", "_", "+", "-", "*", "/", "<", ">", "\\", "|")
            .map { it to it }

private val KeyDivider = Color(0xFF6E7F8D)

/**
 * Strip of quick-insert keys, separated by thin dividers, above the keyboard. With [onRun] set
 * (while the keyboard is up and the Run FAB is hidden) an amber Run key is pinned at the end.
 */
@Composable
fun SymbolBar(onInsert: (String) -> Unit, modifier: Modifier = Modifier, onRun: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().background(BarBlue), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SYMBOL_KEYS.forEachIndexed { index, (label, text) ->
                if (index > 0) VerticalDivider(Modifier.height(22.dp), color = KeyDivider)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(44.dp)
                        .widthIn(min = 52.dp)
                        .clickable { onInsert(text) }
                        .padding(horizontal = 12.dp),
                ) {
                    Text(label, fontFamily = FontFamily.Monospace, fontSize = 17.sp, color = Color.White)
                }
            }
        }
        if (onRun != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 56.dp, height = 44.dp)
                    .background(RunAmber)
                    .clickable(onClick = onRun),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Run", tint = Color(0xFF2B2200))
            }
        }
    }
}
