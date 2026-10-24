package dev.skyloft.skypython.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Label shown on the key → text it inserts. Tab inserts 4 spaces. */
val SYMBOL_KEYS: List<Pair<String, String>> =
    listOf("Tab" to "    ") + listOf("(", ")", "[", "]", "{", "}", ":", "\"", "'", "#", "=", ".").map { it to it }

/** Row of quick-insert keys shown above the soft keyboard. */
@Composable
fun SymbolBar(onInsert: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((label, text) in SYMBOL_KEYS) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(44.dp)
                        .widthIn(min = 40.dp)
                        .clickable { onInsert(text) }
                        .padding(horizontal = 10.dp),
                ) {
                    Text(label, fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
