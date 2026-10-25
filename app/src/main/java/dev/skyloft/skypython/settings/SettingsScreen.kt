package dev.skyloft.skypython.settings

import dev.skyloft.skypython.ui.theme.CodeKeyword
import dev.skyloft.skypython.ui.theme.CodeBlue
import dev.skyloft.skypython.ui.theme.CodeControl
import dev.skyloft.skypython.ui.theme.CodeString
import dev.skyloft.skypython.ui.theme.CodeText
import dev.skyloft.skypython.ui.theme.SlatePanel
import dev.skyloft.skypython.ui.theme.OrangeSoft
import dev.skyloft.skypython.ui.theme.MutedText
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import dev.skyloft.skypython.ui.theme.CodeFont
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
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.DataArray
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.skyloft.skypython.R
import kotlinx.coroutines.delay
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.ui.theme.SlateLine
import dev.skyloft.skypython.ui.theme.Slate
import dev.skyloft.skypython.ui.theme.IconTile
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.brandSwitchColors
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import dev.skyloft.skypython.locale.AppLanguage
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.ui.theme.Orange

/** Settings list. Changes are saved immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val settings by store.settings.collectAsState(initial = null)
    // Not this screen's scope: a change made just before leaving must still be written.
    fun update(transform: (EditorSettings) -> EditorSettings) = store.launchUpdate(transform)

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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_section_editor))
            FontSizeRow(
                sizeSp = s.fontSizeSp,
                min = MIN_FONT_SIZE_SP,
                max = MAX_FONT_SIZE_SP,
                onChange = { sp -> update { it.copy(fontSizeSp = clampFontSize(sp)) } },
            )
            ChoiceRow(
                icon = Icons.Outlined.FontDownload,
                title = stringResource(R.string.settings_font_family),
                selected = s.codeFont,
                options = CodeFontChoice.entries,
                label = { it.label() },
                onSelect = { v -> update { it.copy(codeFont = v) } },
            )
            CodePreview(s.fontSizeSp, s.codeFont)
            Text(
                stringResource(R.string.settings_pinch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
            )
            SwitchRow(
                icon = Icons.AutoMirrored.Outlined.WrapText,
                title = stringResource(R.string.settings_word_wrap),
                summary = stringResource(R.string.settings_word_wrap_summary),
                checked = s.wordWrap,
                onChange = { v -> update { it.copy(wordWrap = v) } },
            )
            SwitchRow(
                icon = Icons.Outlined.FormatListNumbered,
                title = stringResource(R.string.settings_line_numbers),
                summary = stringResource(R.string.settings_line_numbers_summary),
                checked = s.lineNumbers,
                onChange = { v -> update { it.copy(lineNumbers = v) } },
            )
            SwitchRow(
                icon = Icons.Outlined.LightMode,
                title = stringResource(R.string.settings_keep_screen_on),
                summary = stringResource(R.string.settings_keep_screen_on_summary),
                checked = s.keepScreenOn,
                onChange = { v -> update { it.copy(keepScreenOn = v) } },
            )
            SwitchRow(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(R.string.settings_code_completion),
                summary = stringResource(R.string.settings_code_completion_summary),
                checked = s.codeCompletion,
                onChange = { v -> update { it.copy(codeCompletion = v) } },
            )
            SwitchRow(
                icon = Icons.Outlined.DataArray,
                title = stringResource(R.string.settings_auto_close),
                summary = stringResource(R.string.settings_auto_close_summary),
                checked = s.autoClosePairs,
                onChange = { v -> update { it.copy(autoClosePairs = v) } },
            )
            SwitchRow(
                icon = Icons.Outlined.Keyboard,
                title = stringResource(R.string.settings_keyboard_suggestions),
                summary = stringResource(R.string.settings_keyboard_suggestions_summary),
                checked = s.keyboardSuggestions,
                onChange = { v -> update { it.copy(keyboardSuggestions = v) } },
            )
            SymbolKeysRow(
                saved = s.symbolKeys,
                onSave = { spec -> update { it.copy(symbolKeys = spec) } },
            )
            SectionDivider()
            SectionTitle(stringResource(R.string.settings_section_terminal))
            FontSizeRow(
                sizeSp = s.terminalFontSizeSp,
                min = MIN_TERMINAL_FONT_SP,
                max = MAX_TERMINAL_FONT_SP,
                onChange = { sp -> update { it.copy(terminalFontSizeSp = clampTerminalFontSize(sp)) } },
            )
            ChoiceRow(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_terminal_theme),
                selected = s.terminalTheme,
                options = TerminalTheme.entries,
                label = { it.label() },
                onSelect = { v -> update { it.copy(terminalTheme = v) } },
            )
            SectionDivider()
            SectionTitle(stringResource(R.string.settings_section_app))
            LanguageRow()
            SwitchRow(
                icon = Icons.Outlined.ViewDay,
                title = stringResource(R.string.settings_bottom_nav),
                summary = stringResource(R.string.settings_bottom_nav_summary),
                checked = s.bottomNavBar,
                onChange = { v -> update { it.copy(bottomNavBar = v) } },
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(top = 8.dp), color = SlateLine)
}

@Composable
private fun CodeFontChoice.label(): String = when (this) {
    CodeFontChoice.JetBrainsMono -> stringResource(R.string.settings_font_jetbrains_mono)
    CodeFontChoice.SystemMono -> stringResource(R.string.settings_font_system_mono)
}

@Composable
private fun TerminalTheme.label(): String = when (this) {
    TerminalTheme.Dark -> stringResource(R.string.settings_terminal_theme_dark)
    TerminalTheme.Black -> stringResource(R.string.settings_terminal_theme_black)
}

/** A setting with a few named values: the current one on the right, a menu to pick another. */
@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    title: String,
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.DropdownList) { open = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconTile(icon, size = 40.dp)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(selected), style = MaterialTheme.typography.bodyMedium, color = OrangeSoft)
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MutedText, modifier = Modifier.padding(start = 4.dp).size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option)) },
                        trailingIcon = if (option == selected) {
                            { Icon(Icons.Outlined.Check, contentDescription = null, tint = OrangeSoft) }
                        } else {
                            null
                        },
                        onClick = { open = false; onSelect(option) },
                    )
                }
            }
        }
    }
}

/**
 * Free-text list of symbol-bar keys. The field keeps its own text (spaces the user is still
 * typing must not be normalized away) and saves after a short pause, or at once when the user
 * leaves the screen ([SymbolKeysDraft]).
 */
@Composable
private fun SymbolKeysRow(saved: String, onSave: (String) -> Unit) {
    val draft = remember { SymbolKeysDraft(saved) }
    var text by remember { mutableStateOf(saved) }
    val currentOnSave by rememberUpdatedState(onSave)
    val saveNow = {
        draft.toSave()?.let { spec ->
            draft.saving(spec)
            currentOnSave(spec)
        }
    }
    // Changed elsewhere (another device row, a restore): show it; our own save echoing back is not.
    LaunchedEffect(saved) {
        draft.storeChanged(saved)
        text = draft.text
    }
    LaunchedEffect(text) {
        draft.edit(text)
        if (draft.toSave() == null) return@LaunchedEffect
        delay(600)
        saveNow()
    }
    DisposableEffect(draft) { onDispose { saveNow() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(Icons.Outlined.SpaceBar, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_symbol_keys), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_symbol_keys_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = CodeFont),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        TextButton(
            onClick = {
                text = DEFAULT_SYMBOL_KEYS
                onSave(DEFAULT_SYMBOL_KEYS)
            },
            enabled = normalizeSymbolKeys(text) != DEFAULT_SYMBOL_KEYS,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.settings_symbol_keys_reset)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    SectionLabel(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** Font size with − / + steppers in a quiet pill (each button a full 48dp touch target). */
@Composable
private fun FontSizeRow(sizeSp: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Outlined.FormatSize, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_font_size), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_font_size_range, min, max),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier
                .background(SlatePanel, RoundedCornerShape(24.dp))
                .border(1.dp, SlateLine, RoundedCornerShape(24.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onChange(sizeSp - 1) }, enabled = sizeSp > min) {
                Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.settings_smaller), modifier = Modifier.size(20.dp))
            }
            Text(
                "$sizeSp",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { onChange(sizeSp + 1) }, enabled = sizeSp < max) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.settings_larger), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** One line of highlighted Python at the chosen size and font, so the user sees the effect immediately. */
@Composable
private fun CodePreview(sizeSp: Int, font: CodeFontChoice) {
    val code = remember {
        buildAnnotatedString {
            withStyle(SpanStyle(color = CodeKeyword)) { append("def ") }
            withStyle(SpanStyle(color = CodeBlue)) { append("greet") }
            append("(name):  ")
            withStyle(SpanStyle(color = CodeControl)) { append("return ") }
            withStyle(SpanStyle(color = CodeString)) { append("f\"Hi {name}\"") }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Slate, RoundedCornerShape(12.dp))
            .border(1.dp, SlateLine, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            code,
            fontFamily = if (font == CodeFontChoice.SystemMono) FontFamily.Monospace else CodeFont,
            fontSize = sizeSp.sp,
            color = CodeText,
            softWrap = false,
        )
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
        IconTile(Icons.Outlined.Language, size = 40.dp)
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
                            colors = RadioButtonDefaults.colors(selectedColor = Orange),
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
