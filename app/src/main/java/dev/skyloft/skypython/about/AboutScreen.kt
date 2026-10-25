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

private const val GITHUB_URL = "https://github.com/sevorp-dev/SkyPython"
private const val TELEGRAM_URL = "https://t.me/SkyLoft_Studio"

private data class Notice(val name: String, val license: String)

private val NOTICES = listOf(
    Notice("CPython", "Python Software Foundation License"),
    Notice("Sora Editor", "LGPL-2.1"),
    Notice("MagicPython grammar", "MIT"),
    Notice("VS Code Dark+ theme", "MIT"),
    Notice("Termux terminal-emulator and terminal-view", "Apache-2.0"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    Scaffold(
        topBar = {
            SkyTopBar(title = "About", onBack = onBack)
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
                BrandText("SkyPython", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                Row {
                    Text("Python development, ", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = TextBright)
                    BrandText("anywhere.", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text("Version $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A free, open-source Python 3 IDE for Android. " +
                        "Write and run Python scripts on your phone, completely offline.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
            SectionTitle("Links")
            LinkRow(Icons.Filled.Code, "Source code on GitHub", GITHUB_URL) { openUrl(context, GITHUB_URL) }
            LinkRow(Icons.AutoMirrored.Filled.Send, "Telegram channel", TELEGRAM_URL) { openUrl(context, TELEGRAM_URL) }
            HorizontalDivider()
            SectionTitle("License")
            ListItem(
                leadingContent = { OutlinedIconTile(Icons.Filled.Gavel, size = 40.dp, tint = SoftViolet) },
                headlineContent = { Text("Apache License 2.0") },
                supportingContent = { Text("SkyPython is free software; see LICENSE in the source code.") },
            )
            HorizontalDivider()
            SectionTitle("Third-party software")
            for (n in NOTICES) {
                ListItem(
                    headlineContent = { Text(n.name) },
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
                        " · Ideas. Code. Future.",
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
        Toast.makeText(context, "No app can open $url", Toast.LENGTH_SHORT).show()
    }
}
