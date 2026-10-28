package dev.skyloft.skypython.editor

import dev.skyloft.skypython.ui.theme.SkyTheme
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import dev.skyloft.skypython.ui.theme.CodeFont
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
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Search
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
import dev.skyloft.skypython.ui.theme.RunButton

/** The default keys (Settings can change them, see [parseSymbolKeys]). */
val SYMBOL_KEYS: List<SymbolKey> = parseSymbolKeys(DEFAULT_SYMBOL_KEYS)

/** Commands on the "edit actions" row of the bar. [label] is `#` for comment, else an icon. */
enum class EditAction(@StringRes val description: Int, val icon: ImageVector?, val label: String? = null) {
    Undo(R.string.editor_undo, Icons.AutoMirrored.Outlined.Undo),
    Redo(R.string.editor_redo, Icons.AutoMirrored.Outlined.Redo),
    Comment(R.string.editor_action_comment, null, "#"),
    Indent(R.string.editor_action_indent, Icons.AutoMirrored.Outlined.FormatIndentIncrease),
    Outdent(R.string.editor_action_outdent, Icons.AutoMirrored.Outlined.FormatIndentDecrease),
    MoveUp(R.string.editor_action_move_up, Icons.Outlined.ArrowUpward),
    MoveDown(R.string.editor_action_move_down, Icons.Outlined.ArrowDownward),
    Duplicate(R.string.editor_action_duplicate, Icons.Outlined.ContentCopy),
    DeleteLine(R.string.editor_action_delete_line, Icons.Outlined.DeleteSweep),
    Find(R.string.find_and_replace, Icons.Outlined.Search),
    GoToLine(R.string.go_to_line_title, Icons.Outlined.FormatListNumbered),
}

private val KeyDivider: Color @Composable get() = SkyTheme.colors.line

/**
 * Strip of quick-insert [keys] above the keyboard. Left: a key that flips the strip to edit
 * actions ([EditAction]: comment, indent, move line …) and back. The scrolling middle starts
 * with ← → and undo / redo (the most used, so they are in reach without scrolling), then the
 * symbols. Right, pinned: the keyboard show / hide key and, with [onRun] set (keyboard up), a
 * small orange Run key.
 */
@Composable
fun SymbolBar(
    onInsert: (String) -> Unit,
    onAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier,
    keys: List<SymbolKey> = SYMBOL_KEYS,
    keyboardVisible: Boolean = false,
    onToggleKeyboard: () -> Unit = {},
    onCursorLeft: () -> Unit = {},
    onCursorRight: () -> Unit = {},
    onRun: (() -> Unit)? = null,
) {
    var actions by rememberSaveable { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        BarKey(
            onClick = { actions = !actions },
            description = stringResource(if (actions) R.string.editor_actions_hide else R.string.editor_actions_show),
            selected = actions,
        ) {
            Icon(Icons.Outlined.EditNote, contentDescription = null, tint = if (actions) SkyTheme.colors.accentText else KeyText)
        }
        VerticalDivider(Modifier.height(24.dp), color = KeyDivider)
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actions) {
                EditAction.entries.forEach { action ->
                    BarKey(onClick = { onAction(action) }, description = stringResource(action.description)) {
                        if (action.icon != null) {
                            Icon(action.icon, contentDescription = null, tint = KeyText, modifier = Modifier.size(22.dp))
                        } else {
                            Text(action.label.orEmpty(), fontFamily = CodeFont, fontSize = 16.sp, color = KeyText)
                        }
                    }
                }
            } else {
                BarKey(onClick = onCursorLeft, description = stringResource(R.string.editor_cursor_left)) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null, tint = KeyText)
                }
                BarKey(onClick = onCursorRight, description = stringResource(R.string.editor_cursor_right)) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = KeyText)
                }
                BarKey(onClick = { onAction(EditAction.Undo) }, description = stringResource(R.string.editor_undo)) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, tint = KeyText, modifier = Modifier.size(20.dp))
                }
                BarKey(onClick = { onAction(EditAction.Redo) }, description = stringResource(R.string.editor_redo)) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = null, tint = KeyText, modifier = Modifier.size(20.dp))
                }
                VerticalDivider(Modifier.height(24.dp), color = KeyDivider)
                keys.forEach { key ->
                    BarKey(onClick = { onInsert(key.text) }, description = null) {
                        Text(key.label, fontFamily = CodeFont, fontSize = 16.sp, color = KeyText)
                    }
                }
            }
        }
        VerticalDivider(Modifier.height(24.dp), color = KeyDivider)
        BarKey(
            onClick = onToggleKeyboard,
            description = stringResource(if (keyboardVisible) R.string.editor_keyboard_hide else R.string.editor_keyboard_show),
        ) {
            Icon(
                if (keyboardVisible) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
                contentDescription = null,
                tint = KeyText,
                modifier = Modifier.size(22.dp),
            )
        }
        if (onRun != null) {
            RunButton(
                onClick = onRun,
                size = 36.dp,
                iconSize = 22.dp,
                elevation = 0.dp,
                modifier = Modifier.padding(start = 2.dp, end = 10.dp),
            )
        }
    }
}

private val KeyText: Color @Composable get() = SkyTheme.colors.text.copy(alpha = 0.9f)

/** Keycaps: one step above the bar ([SkyColors.bar]), with a hairline edge. */
private val KeyCap: Color @Composable get() = SkyTheme.colors.panel
private val KeyEdge: Color @Composable get() = SkyTheme.colors.line

/**
 * One key: at least 40dp wide (+4dp gap), a soft raised keycap on the bar so keys read as keys,
 * orange-washed when [selected].
 */
@Composable
private fun BarKey(
    onClick: () -> Unit,
    description: String?,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 40.dp)
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .clip(shape)
            .background(if (selected) SkyTheme.colors.accentTint else KeyCap)
            .border(1.dp, if (selected) SkyTheme.colors.accent.copy(alpha = 0.45f) else KeyEdge, shape)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 8.dp),
    ) { content() }
}
