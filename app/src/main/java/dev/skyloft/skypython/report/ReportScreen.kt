package dev.skyloft.skypython.report

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.SkyTopBar
import dev.skyloft.skypython.ui.theme.brandButtonColors
import dev.skyloft.skypython.ui.theme.brandSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

const val REPORT_TEXT_TAG = "report_text"
const val REPORT_DESCRIPTION_TAG = "report_description"

/**
 * "Report a problem": the whole report as text, reviewed before anything leaves the phone.
 * Sending is always the user's action (a browser, a share target or the clipboard); the app
 * itself makes no network call.
 *
 * @param reportId a saved crash or a recent error to attach; null for a plain report.
 * @param launch starts the issue / share intents (tests capture them).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(
    reportId: String?,
    settings: EditorSettings?,
    onBack: () -> Unit,
    launch: (Context, Intent) -> Boolean = Reports::launch,
) {
    val context = LocalContext.current
    val store = remember { Reports.store(context) }
    var version by remember { mutableStateOf(0) } // bumped when a saved report is deleted
    val available by produceState(emptyList<ProblemReport>(), version) {
        value = withContext(Dispatchers.IO) { (store.list() + ErrorReporter.hub.recent()).sortedByDescending { it.timeMillis } }
    }
    // Keyed on the report asked for: opening the screen again for another one must not keep the old choice.
    var selectedId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    val manual = remember { Reports.manual() }
    val selected = available.firstOrNull { it.id == selectedId } ?: ErrorReporter.hub.find(selectedId.orEmpty())
    val report = selected ?: manual

    var description by rememberSaveable { mutableStateOf("") }
    var includeDevice by rememberSaveable { mutableStateOf(true) }
    val info by produceState<List<InfoSection>?>(null) {
        value = withContext(Dispatchers.IO) { DeviceInfo.collect(context, settings) }
    }
    val ready = !includeDevice || info != null
    val deviceInfo = if (includeDevice) info.orEmpty() else null
    // What is sent: always built from what is in the field right now.
    val currentText = { ReportFormatter.format(report, description, deviceInfo) }
    // The preview below follows the description once typing pauses: formatting the report and
    // laying its long text out again on every keystroke made typing lag.
    var previewDescription by remember { mutableStateOf(description) }
    LaunchedEffect(Unit) {
        snapshotFlow { description }.collectLatest {
            if (it != previewDescription) delay(PREVIEW_DELAY_MS)
            previewDescription = it
        }
    }
    val text = remember(report, previewDescription, deviceInfo) { ReportFormatter.format(report, previewDescription, deviceInfo) }
    val scope = rememberCoroutineScope()

    val subject = stringResource(R.string.report_share_subject)
    val chooserTitle = stringResource(R.string.report_share_chooser)
    val summary = stringResource(R.string.report_share_summary, ReportFormatter.title(report))

    Scaffold(topBar = { SkyTopBar(title = stringResource(R.string.report_problem), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrivacyNote()

            if (available.isNotEmpty()) {
                SectionLabel(stringResource(R.string.report_attach))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selected == null,
                        onClick = { selectedId = null },
                        label = { Text(stringResource(R.string.report_none)) },
                    )
                    available.forEach { r ->
                        FilterChip(
                            selected = r.id == selected?.id,
                            onClick = { selectedId = r.id },
                            label = { Text(chipLabel(r)) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.report_description_label)) },
                placeholder = { Text(stringResource(R.string.report_description_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag(REPORT_DESCRIPTION_TAG),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.report_include_device), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.report_include_device_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = includeDevice, onCheckedChange = { includeDevice = it }, colors = brandSwitchColors())
            }

            if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = {
                        val link = IssueUrl.build(
                            title = ReportFormatter.title(report),
                            description = description.trim(),
                            report = ReportFormatter.essentials(report),
                            device = if (includeDevice) ReportFormatter.deviceSummary(info.orEmpty()) else "",
                        )
                        Reports.copy(context, currentText())
                        if (launch(context, Reports.issueIntent(link.url))) {
                            toast(context, if (link.truncated) R.string.report_issue_truncated else R.string.report_issue_copied)
                        } else {
                            toast(context, R.string.report_no_browser)
                        }
                    },
                    enabled = ready,
                    colors = brandButtonColors(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.report_open_issue))
                }
                OutlinedButton(
                    onClick = {
                        val shared = currentText()
                        scope.launch {
                            // A long report is written to a file first.
                            val intent = withContext(Dispatchers.IO) { Reports.shareIntent(context, shared, subject, summary, chooserTitle) }
                            if (!launch(context, intent)) toast(context, R.string.share_no_app)
                        }
                    },
                    enabled = ready,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.report_share))
                }
                OutlinedButton(
                    onClick = {
                        Reports.copy(context, currentText())
                        toast(context, R.string.report_copied)
                    },
                    enabled = ready,
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.report_copy))
                }
            }

            if (selected?.kind == ReportKind.Crash) {
                TextButton(onClick = {
                    val id = selected.id
                    scope.launch {
                        withContext(Dispatchers.IO) { store.remove(id) } // reads every saved report
                        selectedId = null
                        version++
                        toast(context, R.string.report_deleted)
                    }
                }) { Text(stringResource(R.string.report_delete)) }
            }

            SectionLabel(stringResource(R.string.report_full_text))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp).testTag(REPORT_TEXT_TAG),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PrivacyNote() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.report_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun chipLabel(r: ProblemReport): String {
    val time = remember(r.timeMillis) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.timeMillis)) }
    return when (r.kind) {
        ReportKind.Crash -> stringResource(R.string.report_kind_crash, time)
        else -> stringResource(R.string.report_kind_error, whereLabel(r.where), time)
    }
}

/** The component in the user's language, for the banner and the chips. */
@Composable
fun whereLabel(where: String?): String = stringResource(
    when (where) {
        ErrorReporter.Where.PYTHON -> R.string.report_where_python
        ErrorReporter.Where.TERMINAL -> R.string.report_where_terminal
        ErrorReporter.Where.LANGUAGE_SERVER -> R.string.report_where_language_server
        ErrorReporter.Where.DEBUGGER -> R.string.report_where_debugger
        ErrorReporter.Where.PIP -> R.string.report_where_pip
        else -> R.string.report_where_app
    },
)

/** How long the preview waits for typing to pause. */
private const val PREVIEW_DELAY_MS = 300L

private fun toast(context: Context, res: Int) = Toast.makeText(context, res, Toast.LENGTH_LONG).show()
