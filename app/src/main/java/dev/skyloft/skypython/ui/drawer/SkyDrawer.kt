package dev.skyloft.skypython.ui.drawer

import dev.skyloft.skypython.ui.theme.SkyTheme
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.skyloft.skypython.ui.theme.CodeFont
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.editor.CursorPosition
import dev.skyloft.skypython.ui.theme.BrandText
import dev.skyloft.skypython.ui.theme.Chevron
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.update.UpdateDot

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
    DrawerSection(
        "Workspace",
        R.string.drawer_section_workspace,
        listOf(DrawerAction.Projects, DrawerAction.Editor, DrawerAction.Terminal),
    ),
    DrawerSection(
        "Tools",
        R.string.drawer_section_tools,
        listOf(DrawerAction.Interpreter, DrawerAction.Debug, DrawerAction.Pip, DrawerAction.Share, DrawerAction.Samples),
    ),
    DrawerSection("Settings", R.string.drawer_section_settings, listOf(DrawerAction.Settings, DrawerAction.Report, DrawerAction.About)),
)

@StringRes
private fun DrawerAction.labelRes(): Int = when (this) {
    DrawerAction.Projects -> R.string.drawer_projects
    DrawerAction.Editor -> R.string.drawer_editor
    DrawerAction.Interpreter -> R.string.title_interpreter
    DrawerAction.Terminal -> R.string.title_terminal
    DrawerAction.Pip -> R.string.title_pip
    DrawerAction.Share -> R.string.title_share
    DrawerAction.Samples -> R.string.title_samples
    DrawerAction.Settings -> R.string.title_settings
    DrawerAction.Report -> R.string.report_problem
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

private fun DrawerAction.icon(): ImageVector = when (this) {
    DrawerAction.Projects -> Icons.Outlined.Folder
    DrawerAction.Editor -> Icons.Outlined.Code
    DrawerAction.Interpreter -> Icons.Outlined.DataObject
    DrawerAction.Terminal -> Icons.Outlined.Terminal
    DrawerAction.Pip -> Icons.Outlined.Inventory2
    DrawerAction.Share -> Icons.Outlined.Share
    DrawerAction.Samples -> Icons.Outlined.School
    DrawerAction.Settings -> Icons.Outlined.Settings
    DrawerAction.Report -> Icons.Outlined.Feedback
    DrawerAction.About -> Icons.Outlined.Info
    DrawerAction.Debug -> Icons.Outlined.BugReport
}

/**
 * Content of the left tool drawer: brand header (mark, name, version, ✕), the open file, the
 * Workspace / Tools / Settings sections and a footer with the studio's links. [current] is the
 * screen the drawer was opened from (highlighted). The footer sits at the bottom when there is
 * room and follows the list (scrolling with it) on short screens.
 */
@Composable
fun SkyDrawer(
    fileName: String?,
    cursor: CursorPosition,
    onClose: () -> Unit = {},
    current: DrawerAction = DrawerAction.Editor,
    /** `Class.method` around the cursor, from the outline; null when unknown or at top level. */
    symbol: String? = null,
    /** The open project's name, under the file card; null hides the line. */
    projectName: String? = null,
    /** Rows that get an orange dot (About, when an update is waiting). */
    badges: Set<DrawerAction> = emptySet(),
    onAction: (DrawerAction) -> Unit,
) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
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
        modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 360.dp),
        drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        drawerContainerColor = SkyTheme.colors.window,
        windowInsets = WindowInsets(0),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.ic_skypython_mark),
                contentDescription = null,
                modifier = Modifier.size(width = 44.dp, height = 31.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BrandText(stringResource(R.string.app_name), fontSize = 19.sp)
                Text("v$version", color = SkyTheme.colors.mutedText, fontSize = 12.sp)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close), tint = SkyTheme.colors.mutedText)
            }
        }
        FileCard(header, symbol)
        if (projectName != null) {
            Row(
                Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = SkyTheme.colors.accentText, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.drawer_project, projectName),
                    color = SkyTheme.colors.mutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Hairline(Modifier.padding(top = 12.dp))
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
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        DRAWER_SECTIONS.forEachIndexed { index, section ->
                            if (index > 0) Hairline(Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                            SectionLabel(
                                stringResource(section.titleRes),
                                Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp),
                            )
                            section.actions.forEach { action ->
                                DrawerItem(action, selected = action == current, badge = action in badges) { onAction(action) }
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

/** The open file and the cursor, on a quiet card under the brand row. */
@Composable
private fun FileCard(header: DrawerHeader, symbol: String?) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(SkyTheme.colors.panel)
            .border(1.dp, SkyTheme.colors.line, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (header.line != null) SkyTheme.colors.accentText else SkyTheme.colors.mutedText,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                header.title,
                color = SkyTheme.colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (header.line != null) {
                Text(
                    header.line + "  ·  " + header.offset.orEmpty(),
                    color = SkyTheme.colors.mutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The function / class around the cursor, under its line.
                if (symbol != null) {
                    Text(
                        stringResource(R.string.drawer_in_symbol, symbol),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontFamily = CodeFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(action: DrawerAction, selected: Boolean, badge: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(if (selected) SkyTheme.colors.accentTint else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            action.icon(),
            contentDescription = null,
            tint = if (selected) SkyTheme.colors.accent else SkyTheme.colors.text.copy(alpha = 0.86f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(action.labelRes()),
            color = if (selected) SkyTheme.colors.accentText else SkyTheme.colors.text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge) {
            UpdateDot()
            Spacer(Modifier.width(8.dp))
        }
        Chevron()
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
        Box(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
            Text(
                stringResource(R.string.drawer_footer_developer),
                color = SkyTheme.colors.mutedText,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { openLink(context, DrawerLinks.DEVELOPER) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun FooterLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = SkyTheme.colors.mutedText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = SkyTheme.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(subtitle, color = SkyTheme.colors.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun appVersion(context: Context): String = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName ?: "?"
} catch (e: Exception) {
    "?"
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.about_no_app_for_link, url), Toast.LENGTH_SHORT).show()
    }
}
