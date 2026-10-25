package dev.skyloft.skypython.report

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * After a crash, the next start asks once: "SkyPython closed unexpectedly. Send a report?"
 * Either answer marks it asked; "Not now" leaves the report on the Report screen.
 */
@Composable
fun CrashPrompt(onSend: (ProblemReport) -> Unit) {
    val context = LocalContext.current
    val store = remember { Reports.store(context) }
    val scope = rememberCoroutineScope()
    var crash by remember { mutableStateOf<ProblemReport?>(null) }
    LaunchedEffect(Unit) { crash = withContext(Dispatchers.IO) { store.unprompted() } }
    val shown = crash ?: return
    val answered = { scope.launch(Dispatchers.IO) { store.markPrompted() }; crash = null }
    AlertDialog(
        onDismissRequest = { answered() },
        title = { Text(stringResource(R.string.crash_dialog_title)) },
        text = { Text(stringResource(R.string.crash_dialog_text)) },
        confirmButton = {
            TextButton(onClick = {
                answered()
                onSend(shown)
            }) { Text(stringResource(R.string.crash_dialog_send)) }
        },
        dismissButton = {
            TextButton(onClick = { answered() }) { Text(stringResource(R.string.crash_dialog_later)) }
        },
    )
}

/** Shows each [ErrorReporter] problem as a snackbar with a "Report" action. */
@Composable
fun ErrorBanner(snackbar: SnackbarHostState, onReport: (ProblemReport) -> Unit) {
    val notice by ErrorReporter.hub.notice.collectAsStateWithLifecycle()
    val current = notice
    val message = current?.let { stringResource(R.string.report_banner, whereLabel(it.where)) }
    val action = stringResource(R.string.report_banner_action)
    LaunchedEffect(current?.id) {
        if (current == null || message == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = action, withDismissAction = true, duration = SnackbarDuration.Long)
        ErrorReporter.hub.dismiss()
        if (result == SnackbarResult.ActionPerformed) onReport(current)
    }
}
