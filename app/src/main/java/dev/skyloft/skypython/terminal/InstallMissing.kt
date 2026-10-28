package dev.skyloft.skypython.terminal

import dev.skyloft.skypython.ui.theme.SkyTheme
import dev.skyloft.skypython.ui.theme.SheetShape
import dev.skyloft.skypython.ui.theme.floatingOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.R
import dev.skyloft.skypython.pip.InstallOutcome
import dev.skyloft.skypython.pip.MissingPackage
import dev.skyloft.skypython.pip.PipOperation
import dev.skyloft.skypython.pip.PipViewModel
import dev.skyloft.skypython.ui.theme.CodeFont
import dev.skyloft.skypython.ui.theme.brandButtonColors

/** Test tag of the "Install x" action under a run that failed on a missing module. */
const val INSTALL_MISSING_TAG = "run_install_missing"

/** The label of the action: "Install requests", or "Install cv2 (opencv-python)" when the names differ. */
@Composable
fun installMissingLabel(missing: MissingPackage): String =
    if (missing.pipName.equals(missing.module, ignoreCase = true)) {
        stringResource(R.string.run_install_missing, missing.pipName)
    } else {
        stringResource(R.string.run_install_missing_as, missing.module, missing.pipName)
    }

/** Above the finished bar: the run stopped on `No module named 'x'`; one tap installs it. */
@Composable
fun InstallMissingRow(missing: MissingPackage, enabled: Boolean, onInstall: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SkyTheme.colors.bar)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.run_missing_module, missing.module),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onInstall,
            enabled = enabled,
            colors = brandButtonColors(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.testTag(INSTALL_MISSING_TAG),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(installMissingLabel(missing), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Installs [missing] with pip ([pip], the app's one) and shows its log live; once installed,
 * offers [onRunAgain]. Dismissing it doesn't stop pip: the Pip screen shows the same run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallMissingSheet(
    missing: MissingPackage,
    pip: PipViewModel,
    onRunAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by pip.state.collectAsStateWithLifecycle()
    val operation = PipOperation.Install(missing.pipName)
    val mine = state.running == operation || state.outcome?.operation == operation
    val outcome = state.outcome?.takeIf { it.operation == operation && state.running == null }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        modifier = Modifier.floatingOutline(SheetShape),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (outcome) {
                    null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    is InstallOutcome.Succeeded -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    is InstallOutcome.Failed -> Icon(Icons.Outlined.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    when (outcome) {
                        null -> stringResource(R.string.pip_running_install, missing.pipName)
                        is InstallOutcome.Succeeded -> stringResource(R.string.pip_installed_spec, missing.pipName)
                        is InstallOutcome.Failed -> stringResource(R.string.pip_failed_install, missing.pipName)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (!mine && state.installing) {
                // pip runs one thing at a time.
                Text(stringResource(R.string.run_install_busy), style = MaterialTheme.typography.bodyMedium)
            }
            val logScroll = rememberScrollState()
            val log = if (mine) state.log else ""
            LaunchedEffect(log.length) { logScroll.scrollTo(logScroll.maxValue) }
            Text(
                log.ifEmpty { " " },
                fontFamily = CodeFont,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 260.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
                    .verticalScroll(logScroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                if (outcome is InstallOutcome.Succeeded) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onRunAgain, colors = brandButtonColors()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.run_again))
                    }
                } else if (outcome is InstallOutcome.Failed) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pip.installNow(missing.pipName) }) { Text(stringResource(R.string.run_install_retry)) }
                }
            }
        }
    }
}
