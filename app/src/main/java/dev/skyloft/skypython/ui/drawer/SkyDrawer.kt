package dev.skyloft.skypython.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.editor.CursorPosition

/** Texts of the drawer header; [line] and [offset] are null when no file is open. */
data class DrawerHeader(val title: String, val line: String?, val offset: String?)

/** Drawer header: `Line: L/N` (1-based line of total) and `Line offset: C` (0-based column). */
fun drawerHeader(fileName: String?, cursor: CursorPosition): DrawerHeader {
    if (fileName == null) return DrawerHeader("No file open", null, null)
    val total = cursor.lineCount.coerceAtLeast(1)
    val line = (cursor.line + 1).coerceIn(1, total)
    return DrawerHeader(fileName, "Line: $line/$total", "Line offset: ${cursor.column.coerceAtLeast(0)}")
}

data class DrawerSection(val title: String, val actions: List<DrawerAction>)

val DRAWER_SECTIONS: List<DrawerSection> = listOf(
    DrawerSection("Run", listOf(DrawerAction.Interpreter, DrawerAction.Terminal)),
    DrawerSection("Tools", listOf(DrawerAction.Pip, DrawerAction.Share, DrawerAction.Samples)),
    DrawerSection("Settings", listOf(DrawerAction.Settings, DrawerAction.About)),
)

private val HeaderBlue = Color(0xFF0B2B3F)
private val SheetColor = Color(0xFF1A242D)
private val SectionGrey = Color(0xFF9AA7B2)
private val ItemText = Color(0xFFE6EDF3)
private val ItemIcon = Color(0xFFC9D3DC)
private val DividerColor = Color(0xFF2C3946)

private fun DrawerAction.icon(): ImageVector = when (this) {
    DrawerAction.Interpreter -> Icons.Outlined.Slideshow
    DrawerAction.Terminal -> Icons.Filled.Terminal
    DrawerAction.Pip -> Icons.AutoMirrored.Filled.LibraryBooks
    DrawerAction.Share -> Icons.Filled.Share
    DrawerAction.Samples -> Icons.Filled.School
    DrawerAction.Settings -> Icons.Filled.Settings
    DrawerAction.About -> Icons.Filled.Info
}

/** Content of the left tool drawer: file/cursor header, then the Run / Tools / Settings sections. */
@Composable
fun SkyDrawer(fileName: String?, cursor: CursorPosition, onAction: (DrawerAction) -> Unit) {
    val header = drawerHeader(fileName, cursor)
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 360.dp),
        drawerShape = RectangleShape,
        drawerContainerColor = SheetColor,
        windowInsets = WindowInsets(0),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(HeaderBlue)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Text(
                header.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (header.line != null) {
                Spacer(Modifier.height(10.dp))
                Text(header.line, color = Color(0xFFCFDCE6), fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(header.offset.orEmpty(), color = Color(0xFFCFDCE6), fontSize = 14.sp)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            DRAWER_SECTIONS.forEachIndexed { index, section ->
                if (index > 0) HorizontalDivider(color = DividerColor)
                Text(
                    section.title,
                    color = SectionGrey,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                )
                section.actions.forEach { action ->
                    DrawerItem(action) { onAction(action) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun DrawerItem(action: DrawerAction, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(action.icon(), contentDescription = null, tint = ItemIcon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(32.dp))
        Text(action.name, color = ItemText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
