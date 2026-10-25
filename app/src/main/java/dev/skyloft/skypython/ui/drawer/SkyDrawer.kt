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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.HorizontalDivider
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
import dev.skyloft.skypython.R
import dev.skyloft.skypython.editor.CursorPosition
import dev.skyloft.skypython.ui.theme.BrandText
import dev.skyloft.skypython.ui.theme.DividerNavy
import dev.skyloft.skypython.ui.theme.GlowLine
import dev.skyloft.skypython.ui.theme.NightSkyBrush
import dev.skyloft.skypython.ui.theme.OutlinedIconTile
import dev.skyloft.skypython.ui.theme.SkyBlue
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.SoftViolet
import dev.skyloft.skypython.ui.theme.TextBright
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

/** Texts of the drawer header; [line] and [offset] are null when no file is open. */
data class DrawerHeader(val title: String, val line: String?, val offset: String?)

/** Texts of the drawer header; English by default, localized by [SkyDrawer]. */
class DrawerHeaderStrings(
    val noFile: String = "No file open",
    val line: (line: Int, total: Int) -> String = { line, total -> "Line: $line/$total" },
    val offset: (column: Int) -> String = { "Line offset: $it" },
)

/** Drawer header: `Line: L/N` (1-based line of total) and `Line offset: C` (0-based column). */
fun drawerHeader(
    fileName: String?,
    cursor: CursorPosition,
    strings: DrawerHeaderStrings = DrawerHeaderStrings(),
): DrawerHeader {
    if (fileName == null) return DrawerHeader(strings.noFile, null, null)
    val total = cursor.lineCount.coerceAtLeast(1)
    val line = (cursor.line + 1).coerceIn(1, total)
    return DrawerHeader(fileName, strings.line(line, total), strings.offset(cursor.column.coerceAtLeast(0)))
}

/** [title] is the English name (tests); [titleRes] is what the drawer shows. */
data class DrawerSection(val title: String, @StringRes val titleRes: Int, val actions: List<DrawerAction>)

val DRAWER_SECTIONS: List<DrawerSection> = listOf(
    DrawerSection("Run", R.string.drawer_section_run, listOf(DrawerAction.Debug, DrawerAction.Interpreter, DrawerAction.Terminal)),
    DrawerSection("Tools", R.string.drawer_section_tools, listOf(DrawerAction.Pip, DrawerAction.Share, DrawerAction.Samples)),
    DrawerSection("Settings", R.string.drawer_section_settings, listOf(DrawerAction.Settings, DrawerAction.About)),
)

@StringRes
private fun DrawerAction.labelRes(): Int = when (this) {
    DrawerAction.Interpreter -> R.string.title_interpreter
    DrawerAction.Terminal -> R.string.title_terminal
    DrawerAction.Pip -> R.string.title_pip
    DrawerAction.Share -> R.string.title_share
    DrawerAction.Samples -> R.string.title_samples
    DrawerAction.Settings -> R.string.title_settings
    DrawerAction.About -> R.string.title_about
    DrawerAction.Debug -> R.string.title_debug
}

private val SheetColor = Color(0xFF0D1536)
private val ItemText = TextBright
private val HeaderSubtext = Color(0xFFC9D4F5)
private val DividerColor = DividerNavy

/** A few fixed "stars" (x, y as fractions of the header, radius in dp) for the night-sky header. */
private val STARS = listOf(
    Triple(0.62f, 0.22f, 1.2f), Triple(0.78f, 0.12f, 0.8f), Triple(0.90f, 0.35f, 1.4f),
    Triple(0.70f, 0.55f, 0.7f), Triple(0.84f, 0.70f, 1.0f), Triple(0.95f, 0.85f, 0.7f),
    Triple(0.55f, 0.80f, 0.6f), Triple(0.45f, 0.30f, 0.6f),
)

private fun DrawerAction.icon(): ImageVector = when (this) {
    DrawerAction.Interpreter -> Icons.Outlined.Slideshow
    DrawerAction.Terminal -> Icons.Filled.Terminal
    DrawerAction.Pip -> Icons.AutoMirrored.Filled.LibraryBooks
    DrawerAction.Share -> Icons.Filled.Share
    DrawerAction.Samples -> Icons.Filled.School
    DrawerAction.Settings -> Icons.Filled.Settings
    DrawerAction.About -> Icons.Filled.Info
    DrawerAction.Debug -> Icons.Filled.BugReport
}

/** Content of the left tool drawer: file/cursor header, then the Run / Tools / Settings sections. */
@Composable
fun SkyDrawer(fileName: String?, cursor: CursorPosition, onAction: (DrawerAction) -> Unit) {
    val noFile = stringResource(R.string.no_file_open)
    val lineFormat = stringResource(R.string.drawer_line)
    val offsetFormat = stringResource(R.string.drawer_line_offset)
    val header = drawerHeader(
        fileName,
        cursor,
        DrawerHeaderStrings(
            noFile = noFile,
            line = { line, total -> String.format(lineFormat, line, total) },
            offset = { String.format(offsetFormat, it) },
        ),
    )
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 360.dp),
        drawerShape = RectangleShape,
        drawerContainerColor = SheetColor,
        windowInsets = WindowInsets(0),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(NightSkyBrush)
                .drawBehind {
                    for ((x, y, r) in STARS) {
                        drawCircle(Color.White.copy(alpha = 0.75f), radius = r.dp.toPx(), center = Offset(size.width * x, size.height * y))
                    }
                }
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                )
                BrandText(stringResource(R.string.app_name), fontSize = 24.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                header.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (header.line != null) {
                Spacer(Modifier.height(6.dp))
                Text(header.line, color = HeaderSubtext, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(header.offset.orEmpty(), color = HeaderSubtext, fontSize = 14.sp)
            }
        }
        GlowLine()
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            DRAWER_SECTIONS.forEachIndexed { index, section ->
                if (index > 0) HorizontalDivider(color = DividerColor)
                SectionLabel(stringResource(section.titleRes), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
                section.actions.forEach { action ->
                    DrawerItem(action, action.ordinal) { onAction(action) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun DrawerItem(action: DrawerAction, index: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        OutlinedIconTile(action.icon(), size = 36.dp, tint = if (index % 2 == 0) SkyBlue else SoftViolet)
        Spacer(Modifier.width(20.dp))
        Text(
            stringResource(action.labelRes()),
            color = ItemText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
