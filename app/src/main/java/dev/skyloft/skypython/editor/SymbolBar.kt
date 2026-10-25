package dev.skyloft.skypython.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.settings.DEFAULT_SYMBOL_KEYS
import dev.skyloft.skypython.settings.SymbolKey
import dev.skyloft.skypython.settings.parseSymbolKeys
import dev.skyloft.skypython.ui.theme.BarNavy
import dev.skyloft.skypython.ui.theme.DividerNavy
import dev.skyloft.skypython.ui.theme.RunButton
import dev.skyloft.skypython.ui.theme.TextBright

/** The default keys (Settings can change them, see [parseSymbolKeys]). */
val SYMBOL_KEYS: List<SymbolKey> = parseSymbolKeys(DEFAULT_SYMBOL_KEYS)

/** Commands on the "edit actions" row of the bar. [label] is `#` for comment, else an icon. */
enum class EditAction(@StringRes val description: Int, val icon: ImageVector?, val label: String? = null) {
    Undo(R.string.editor_undo, Icons.AutoMirrored.Filled.Undo),
    Redo(R.string.editor_redo, Icons.AutoMirrored.Filled.Redo),
    Comment(R.string.editor_action_comment, null, "#"),
    Indent(R.string.editor_action_indent, Icons.AutoMirrored.Filled.FormatIndentIncrease),
    Outdent(R.string.editor_action_outdent, Icons.AutoMirrored.Filled.FormatIndentDecrease),
    MoveUp(R.string.editor_action_move_up, Icons.Filled.ArrowUpward),
    MoveDown(R.string.editor_action_move_down, Icons.Filled.ArrowDownward),
    Duplicate(R.string.editor_action_duplicate, Icons.Filled.ContentCopy),
    DeleteLine(R.string.editor_action_delete_line, Icons.Filled.DeleteSweep),
    Find(R.string.find_and_replace, Icons.Filled.Search),
    GoToLine(R.string.go_to_line_title, Icons.Filled.FormatListNumbered),
}

private val KeyDivider = DividerNavy

/**
 * Strip of quick-insert [keys], separated by thin dividers, above the keyboard. The first key
 * flips the strip to edit actions ([EditAction]: comment, indent, move line …) and back.
 * With [onRun] set (while the keyboard is up and the Run FAB is hidden) a round solid-blue Run key
 * is pinned at the end.
 */
@Composable
fun SymbolBar(
    onInsert: (String) -> Unit,
    onAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier,
    keys: List<SymbolKey> = SYMBOL_KEYS,
    onRun: (() -> Unit)? = null,
) {
    var actions by rememberSaveable { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().background(BarNavy), verticalAlignment = Alignment.CenterVertically) {
        BarKey(
            onClick = { actions = !actions },
            description = stringResource(if (actions) R.string.editor_actions_hide else R.string.editor_actions_show),
        ) {
            if (actions) {
                Text("{ }", fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = TextBright)
            } else {
                Icon(Icons.Filled.EditNote, contentDescription = null, tint = TextBright)
            }
        }
        VerticalDivider(Modifier.height(28.dp), color = KeyDivider)
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actions) {
                EditAction.entries.forEachIndexed { index, action ->
                    if (index > 0) VerticalDivider(Modifier.height(22.dp), color = KeyDivider)
                    BarKey(onClick = { onAction(action) }, description = stringResource(action.description)) {
                        if (action.icon != null) {
                            Icon(action.icon, contentDescription = null, tint = TextBright, modifier = Modifier.size(22.dp))
                        } else {
                            Text(action.label.orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = TextBright)
                        }
                    }
                }
            } else {
                keys.forEachIndexed { index, key ->
                    if (index > 0) VerticalDivider(Modifier.height(22.dp), color = KeyDivider)
                    BarKey(onClick = { onInsert(key.text) }, description = null) {
                        Text(key.label, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = TextBright)
                    }
                }
            }
        }
        if (onRun != null) {
            RunButton(
                onClick = onRun,
                size = 36.dp,
                iconSize = 22.dp,
                elevation = 0.dp,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

@Composable
private fun BarKey(onClick: () -> Unit, description: String?, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(44.dp)
            .widthIn(min = 52.dp)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp),
    ) { content() }
}
