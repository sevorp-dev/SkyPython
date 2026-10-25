package dev.skyloft.skypython.ui.drawer

import androidx.compose.material.icons.outlined.BugReport
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.editor.CursorPosition
import dev.skyloft.skypython.ui.theme.SlateBar
import dev.skyloft.skypython.ui.theme.BrandText
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.IconTile
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.TextBright

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

/** Where the drawer footer's rows lead. */
object DrawerLinks {
    const val TELEGRAM = "https://t.me/SkyLoft_Studio"
    const val GITHUB = "https://github.com/sevorp-dev/SkyPython"
    const val DEVELOPER = "https://github.com/sevorp-dev"

    val ALL: List<String> = listOf(TELEGRAM, GITHUB, DEVELOPER)
}

/** The sheet: one step below the header ([SlateBar]). */
private val SheetColor = Color(0xFF121929)
private val HeaderColor = SlateBar
private val ItemText = TextBright

private fun DrawerAction.icon(): ImageVector = when (this) {
    DrawerAction.Interpreter -> Icons.Outlined.Slideshow
    DrawerAction.Terminal -> Icons.Outlined.Terminal
    DrawerAction.Pip -> Icons.AutoMirrored.Outlined.LibraryBooks
    DrawerAction.Share -> Icons.Outlined.Share
    DrawerAction.Samples -> Icons.Outlined.School
    DrawerAction.Settings -> Icons.Outlined.Settings
    DrawerAction.About -> Icons.Outlined.Info
    DrawerAction.Debug -> Icons.Outlined.BugReport
}

/**
 * Content of the left tool drawer: file/cursor header, the Run / Tools / Settings sections and a
 * footer with the studio's links. The footer sits at the bottom when there is room and follows
 * the list (scrolling with it) on short screens.
 */
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
                .background(HeaderColor)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.width(6.dp))
                BrandText(stringResource(R.string.app_name), fontSize = 20.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                header.title,
                color = TextBright,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (header.line != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    header.line + "  ·  " + header.offset.orEmpty(),
                    color = MutedText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Hairline()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val minHeight = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minHeight).navigationBarsPadding(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        DRAWER_SECTIONS.forEachIndexed { index, section ->
                            if (index > 0) Hairline(Modifier.padding(vertical = 4.dp))
                            SectionLabel(
                                stringResource(section.titleRes),
                                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                            section.actions.forEach { action ->
                                DrawerItem(action) { onAction(action) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    DrawerFooter()
                }
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
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        IconTile(action.icon(), size = 36.dp)
        Spacer(Modifier.width(16.dp))
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

/** Telegram and GitHub rows and a small "Developed by" caption, under a hairline. */
@Composable
private fun DrawerFooter() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Spacer(Modifier.height(4.dp))
        FooterLink(
            icon = ImageVector.vectorResource(R.drawable.ic_paper_plane),
            title = stringResource(R.string.drawer_footer_studio),
            subtitle = stringResource(R.string.about_telegram),
        ) { openLink(context, DrawerLinks.TELEGRAM) }
        FooterLink(
            icon = Icons.Outlined.Code,
            title = stringResource(R.string.drawer_footer_github),
            subtitle = stringResource(R.string.drawer_footer_repo),
        ) { openLink(context, DrawerLinks.GITHUB) }
        Text(
            stringResource(R.string.drawer_footer_developer),
            color = MutedText,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(start = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { openLink(context, DrawerLinks.DEVELOPER) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun FooterLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = TextBright, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(subtitle, color = MutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.about_no_app_for_link, url), Toast.LENGTH_SHORT).show()
    }
}
