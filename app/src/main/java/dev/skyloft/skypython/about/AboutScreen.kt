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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import dev.skyloft.skypython.ui.theme.OutlinedIconTile
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.SoftViolet
import dev.skyloft.skypython.ui.theme.TextBright
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.skyloft.skypython.ui.theme.BrandTextBrush
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
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
                    // The banner's night sky, fading into the page.
                    .background(Brush.verticalGradient(listOf(Color(0xFF14309A).copy(alpha = 0.45f), Color(0xFF4A2C9E).copy(alpha = 0.18f), Color.Transparent)))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(128.dp),
                )
                BrandText(stringResource(R.string.app_name), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                // One Text, so a longer translation wraps instead of being cut off.
                val start = stringResource(R.string.about_tagline_start)
                val end = stringResource(R.string.about_tagline_end)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = TextBright, fontWeight = FontWeight.Medium)) { append(start) }
                        withStyle(SpanStyle(brush = BrandTextBrush, fontWeight = FontWeight.Bold)) { append(end) }
                    },
                    fontSize = 18.sp,
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
            HorizontalDivider()
            SectionTitle(stringResource(R.string.about_links))
            LinkRow(Icons.Filled.Code, stringResource(R.string.about_source_code), GITHUB_URL) { openUrl(context, GITHUB_URL) }
            LinkRow(Icons.AutoMirrored.Filled.Send, stringResource(R.string.about_telegram), TELEGRAM_URL) { openUrl(context, TELEGRAM_URL) }
            HorizontalDivider()
            SectionTitle(stringResource(R.string.about_license))
            ListItem(
                leadingContent = { OutlinedIconTile(Icons.Filled.Gavel, size = 40.dp, tint = SoftViolet) },
                headlineContent = { Text("Apache License 2.0") },
                supportingContent = { Text(stringResource(R.string.about_license_text)) },
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.about_third_party))
            for (n in NOTICES) {
                ListItem(
                    headlineContent = { Text(n.name ?: stringResource(n.nameRes)) },
                    supportingContent = { Text(n.license, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            HorizontalDivider()
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandText("SkyLoft Studio", fontSize = 15.sp)
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
    SectionLabel(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, url: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { OutlinedIconTile(icon, size = 40.dp) },
        headlineContent = { Text(title) },
        supportingContent = { Text(url, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
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
