package dev.skyloft.skypython.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import dev.skyloft.skypython.ui.theme.SkyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.ui.theme.DividerNavy
import dev.skyloft.skypython.ui.theme.EditorBackground
import dev.skyloft.skypython.ui.theme.IconTile
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.brandSwitchColors
import kotlinx.coroutines.launch

/** Settings list. Changes are saved immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val settings by store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    fun update(transform: (EditorSettings) -> EditorSettings) {
        scope.launch { store.update(transform) }
    }

    Scaffold(
        topBar = {
            SkyTopBar(title = "Settings", onBack = onBack)
        },
    ) { padding ->
        val s = settings ?: return@Scaffold // wait for the first read so switches don't flicker
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Editor")
            FontSizeRow(
                sizeSp = s.fontSizeSp,
                onChange = { sp -> update { it.copy(fontSizeSp = clampFontSize(sp)) } },
            )
            CodePreview(s.fontSizeSp)
            HorizontalDivider(Modifier.padding(top = 12.dp), color = DividerNavy)
            SwitchRow(
                icon = Icons.AutoMirrored.Filled.WrapText,
                title = "Word wrap",
                summary = "Wrap long lines instead of scrolling sideways",
                checked = s.wordWrap,
                onChange = { v -> update { it.copy(wordWrap = v) } },
            )
            SwitchRow(
                icon = Icons.Filled.FormatListNumbered,
                title = "Line numbers",
                summary = "Show line numbers in the editor",
                checked = s.lineNumbers,
                onChange = { v -> update { it.copy(lineNumbers = v) } },
            )
            SwitchRow(
                icon = Icons.Filled.AutoAwesome,
                title = "Code completion",
                summary = "Suggestions while typing and error underlines",
                checked = s.codeCompletion,
                onChange = { v -> update { it.copy(codeCompletion = v) } },
            )
            SwitchRow(
                icon = Icons.Filled.LightMode,
                title = "Keep screen on",
                summary = "Don't let the screen turn off while editing",
                checked = s.keepScreenOn,
                onChange = { v -> update { it.copy(keepScreenOn = v) } },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    SectionLabel(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun FontSizeRow(sizeSp: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Filled.TextFields, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Font size", style = MaterialTheme.typography.bodyLarge)
            Text(
                "$MIN_FONT_SIZE_SP–$MAX_FONT_SIZE_SP sp",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onChange(sizeSp - 1) }, enabled = sizeSp > MIN_FONT_SIZE_SP) {
            Icon(Icons.Filled.Remove, contentDescription = "Smaller")
        }
        Text(
            "$sizeSp",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange(sizeSp + 1) }, enabled = sizeSp < MAX_FONT_SIZE_SP) {
            Icon(Icons.Filled.Add, contentDescription = "Larger")
        }
    }
}

/** One line of highlighted Python at the chosen size, so the user sees the effect immediately. */
@Composable
private fun CodePreview(sizeSp: Int) {
    val code = remember {
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFF82A8F0))) { append("def ") }
            withStyle(SpanStyle(color = Color(0xFF72C6E4))) { append("greet") }
            append("(name):  ")
            withStyle(SpanStyle(color = Color(0xFFB8A6EE))) { append("return ") }
            withStyle(SpanStyle(color = Color(0xFFE2B48A))) { append("f\"Hi {name}\"") }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(EditorBackground, RoundedCornerShape(12.dp))
            .border(1.dp, DividerNavy, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(code, fontFamily = FontFamily.Monospace, fontSize = sizeSp.sp, color = Color(0xFFD8DEEA), softWrap = false)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconTile(icon, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = brandSwitchColors())
    }
}
