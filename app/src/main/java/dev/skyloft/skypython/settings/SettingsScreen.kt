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
import dev.skyloft.skypython.ui.theme.OutlinedIconTile
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.brandSwitchColors
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.skyloft.skypython.R
import dev.skyloft.skypython.locale.AppLanguage
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.ui.theme.ElectricBlue

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
            SkyTopBar(title = stringResource(R.string.title_settings), onBack = onBack)
        },
    ) { padding ->
        val s = settings ?: return@Scaffold // wait for the first read so switches don't flicker
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_section_editor))
            FontSizeRow(
                sizeSp = s.fontSizeSp,
                onChange = { sp -> update { it.copy(fontSizeSp = clampFontSize(sp)) } },
            )
            CodePreview(s.fontSizeSp)
            HorizontalDivider(Modifier.padding(top = 12.dp), color = DividerNavy)
            SwitchRow(
                icon = Icons.AutoMirrored.Filled.WrapText,
                title = stringResource(R.string.settings_word_wrap),
                summary = stringResource(R.string.settings_word_wrap_summary),
                checked = s.wordWrap,
                onChange = { v -> update { it.copy(wordWrap = v) } },
            )
            SwitchRow(
                icon = Icons.Filled.FormatListNumbered,
                title = stringResource(R.string.settings_line_numbers),
                summary = stringResource(R.string.settings_line_numbers_summary),
                checked = s.lineNumbers,
                onChange = { v -> update { it.copy(lineNumbers = v) } },
            )
            SwitchRow(
                icon = Icons.Filled.LightMode,
                title = stringResource(R.string.settings_keep_screen_on),
                summary = stringResource(R.string.settings_keep_screen_on_summary),
                checked = s.keepScreenOn,
                onChange = { v -> update { it.copy(keepScreenOn = v) } },
            )
            HorizontalDivider(color = DividerNavy)
            SectionTitle(stringResource(R.string.settings_section_app))
            LanguageRow()
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
        OutlinedIconTile(Icons.Filled.TextFields, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_font_size), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_font_size_range, MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onChange(sizeSp - 1) }, enabled = sizeSp > MIN_FONT_SIZE_SP) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.settings_smaller))
        }
        Text(
            "$sizeSp",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange(sizeSp + 1) }, enabled = sizeSp < MAX_FONT_SIZE_SP) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.settings_larger))
        }
    }
}

/** One line of highlighted Python at the chosen size, so the user sees the effect immediately. */
@Composable
private fun CodePreview(sizeSp: Int) {
    val code = remember {
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFF6EA8FF))) { append("def ") }
            withStyle(SpanStyle(color = Color(0xFF5ED3F7))) { append("greet") }
            append("(name):  ")
            withStyle(SpanStyle(color = Color(0xFFC4A1FF))) { append("return ") }
            withStyle(SpanStyle(color = Color(0xFFF5B97A))) { append("f\"Hi {name}\"") }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(EditorBackground, RoundedCornerShape(6.dp))
            .border(1.dp, DividerNavy, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(code, fontFamily = FontFamily.Monospace, fontSize = sizeSp.sp, color = Color(0xFFDCE3F5), softWrap = false)
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
        OutlinedIconTile(icon, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = brandSwitchColors())
    }
}

@Composable
private fun AppLanguage.label(): String = when (this) {
    AppLanguage.System -> stringResource(R.string.language_system)
    AppLanguage.English -> stringResource(R.string.language_english)
    AppLanguage.Russian -> stringResource(R.string.language_russian)
}

/** App language: System / English / Русский. Choosing one recreates the screen in it. */
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val current = remember { AppLocale.current(context) }
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedIconTile(Icons.Filled.Language, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
            Text(current.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = language == current,
                                role = Role.RadioButton,
                                onClick = {
                                    open = false
                                    if (language != current) context.findActivity()?.let { AppLocale.set(it, language) }
                                },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(
                            selected = language == current,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = ElectricBlue),
                        )
                        Text(language.label(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
