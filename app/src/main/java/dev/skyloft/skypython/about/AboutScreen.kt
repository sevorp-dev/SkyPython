package dev.skyloft.skypython.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import dev.skyloft.skypython.ui.theme.CardGroup
import dev.skyloft.skypython.ui.theme.CardDivider
import dev.skyloft.skypython.ui.theme.Chevron
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import dev.skyloft.skypython.ui.theme.SkyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import dev.skyloft.skypython.R
import dev.skyloft.skypython.ui.theme.BrandText
import dev.skyloft.skypython.ui.theme.BrandTextBrush
import dev.skyloft.skypython.ui.theme.OrangeSoft
import dev.skyloft.skypython.ui.theme.IconTile
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.TextBright
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

private const val GITHUB_URL = "https://github.com/sevorp-dev/SkyPython"
private const val TELEGRAM_URL = "https://t.me/SkyLoft_Studio"

/** A third-party component: a plain [name] (a product name) or a [nameRes] that needs translating. */
private data class Notice(val name: String?, @StringRes val nameRes: Int, val license: String)

private val NOTICES = listOf(
    Notice("CPython", 0, "Python Software Foundation License"),
    Notice("Sora Editor", 0, "LGPL-2.1"),
    Notice(null, R.string.about_notice_grammar, "MIT"),
    Notice(null, R.string.about_notice_theme, "MIT"),
    Notice(null, R.string.about_notice_terminal, "Apache-2.0"),
    Notice("jedi", 0, "MIT"),
    Notice("parso", 0, "MIT"),
    Notice("pyflakes", 0, "MIT"),
    Notice("typeshed", 0, "Apache-2.0"),
    Notice("Inter", 0, "SIL Open Font License 1.1"),
    Notice("JetBrains Mono", 0, "SIL Open Font License 1.1"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onReport: () -> Unit = {}) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    Scaffold(
        topBar = {
            SkyTopBar(title = stringResource(R.string.title_about), onBack = onBack)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    painterResource(R.drawable.ic_skypython_mark),
                    contentDescription = null,
                    modifier = Modifier.size(width = 128.dp, height = 90.dp),
                )
                BrandText(stringResource(R.string.app_name), fontSize = 32.sp, fontWeight = FontWeight.Bold, brush = BrandTextBrush)
                // One Text, so a longer translation wraps instead of being cut off.
                val start = stringResource(R.string.about_tagline_start)
                val end = stringResource(R.string.about_tagline_end)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = TextBright)) { append(start) }
                        withStyle(SpanStyle(color = OrangeSoft, fontWeight = FontWeight.Medium)) { append(end) }
                    },
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                )
                Text(stringResource(R.string.about_version, version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_description),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SectionTitle(stringResource(R.string.about_links))
            CardGroup {
                LinkRow(Icons.Outlined.Code, stringResource(R.string.about_source_code), GITHUB_URL) { openUrl(context, GITHUB_URL) }
                CardDivider()
                LinkRow(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.about_telegram), TELEGRAM_URL) { openUrl(context, TELEGRAM_URL) }
                CardDivider()
                ListItem(
                    modifier = Modifier.clickable(onClick = onReport),
                    colors = inCard(),
                    leadingContent = { IconTile(Icons.Outlined.Feedback, size = 40.dp) },
                    headlineContent = { Text(stringResource(R.string.report_problem)) },
                    supportingContent = { Text(stringResource(R.string.about_report_summary), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Chevron() },
                )
            }
            SectionTitle(stringResource(R.string.about_license))
            CardGroup {
                ListItem(
                    colors = inCard(),
                    leadingContent = { IconTile(Icons.Outlined.Gavel, size = 40.dp) },
                    headlineContent = { Text("Apache License 2.0") },
                    supportingContent = { Text(stringResource(R.string.about_license_text), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            SectionTitle(stringResource(R.string.about_third_party))
            CardGroup {
                NOTICES.forEachIndexed { i, n ->
                    if (i > 0) CardDivider(inset = 16.dp)
                    ListItem(
                        colors = inCard(),
                        headlineContent = { Text(n.name ?: stringResource(n.nameRes)) },
                        supportingContent = { Text(n.license, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandText("SkyLoft Studio", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.about_slogan),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.6.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    SectionLabel(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp))
}

/** List items sit on the card's surface, not their own. */
@Composable
private fun inCard() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun LinkRow(icon: ImageVector, title: String, url: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = inCard(),
        leadingContent = { IconTile(icon, size = 40.dp) },
        headlineContent = { Text(title) },
        supportingContent = { Text(url, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
    )
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

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.about_no_app_for_link, url), Toast.LENGTH_SHORT).show()
    }
}
