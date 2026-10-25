package dev.skyloft.skypython.editor

import dev.skyloft.skypython.ui.theme.SheetShape
import dev.skyloft.skypython.ui.theme.floatingOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.ui.theme.CodeFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.lang.OutlineItem
import dev.skyloft.skypython.lang.Outlines
import dev.skyloft.skypython.lang.SymbolKind
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.SectionLabel

/**
 * The file's classes, functions and methods, nested by indentation. [items] null: still loading.
 * Tapping a row jumps to it ([onPick]); [currentLine] (1-based) marks the symbol the cursor is in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(
    items: List<OutlineItem>?,
    currentLine: Int,
    onPick: (OutlineItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        shape = SheetShape,
        containerColor = colors.surfaceContainer,
        modifier = Modifier.testTag("outline_sheet").floatingOutline(SheetShape),
    ) {
        SectionLabel(stringResource(R.string.editor_outline), Modifier.padding(start = 20.dp, bottom = 8.dp))
        Hairline()
        when {
            items == null -> Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.editor_outline_loading), color = colors.onSurfaceVariant)
            }
            items.isEmpty() -> Text(
                stringResource(R.string.editor_outline_empty),
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(24.dp).navigationBarsPadding(),
            )
            else -> {
                val rows = remember(items) { Outlines.flatten(items) }
                val here = remember(items, currentLine) { innermostAt(items, currentLine) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).navigationBarsPadding()) {
                    items(rows) { row -> OutlineRow(row, selected = row.item === here, onClick = { onPick(row.item) }) }
                }
            }
        }
    }
}

private fun innermostAt(items: List<OutlineItem>, line: Int): OutlineItem? {
    var level = items
    var hit: OutlineItem? = null
    while (true) {
        val next = level.lastOrNull { line in it.line..it.endLine } ?: return hit
        hit = next
        level = next.children
    }
}

@Composable
private fun OutlineRow(row: Outlines.Row, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val item = row.item
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primary.copy(alpha = 0.10f) else colors.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(start = 20.dp + 18.dp * row.depth, end = 20.dp, top = 10.dp, bottom = 10.dp),
    ) {
        KindBadge(item.kind)
        Spacer(Modifier.width(12.dp))
        Text(
            item.name,
            color = colors.onSurface,
            fontFamily = CodeFont,
            fontSize = 15.sp,
            fontWeight = if (item.kind == SymbolKind.CLASS) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.editor_outline_line, item.line),
            color = colors.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

/** C / F / M on a soft tonal circle, like the completion list's badges. */
@Composable
private fun KindBadge(kind: SymbolKind) {
    val colors = MaterialTheme.colorScheme
    val tint = when (kind) {
        SymbolKind.CLASS -> colors.tertiary
        else -> colors.primary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(24.dp).background(tint.copy(alpha = 0.16f), CircleShape),
    ) {
        Text(
            when (kind) {
                SymbolKind.CLASS -> "C"
                SymbolKind.FUNCTION -> "F"
                SymbolKind.METHOD -> "M"
            },
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
