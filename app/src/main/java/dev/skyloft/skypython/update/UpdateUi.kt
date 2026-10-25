package dev.skyloft.skypython.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.BuildConfig
import dev.skyloft.skypython.R
import dev.skyloft.skypython.ui.theme.BrandText
import dev.skyloft.skypython.ui.theme.CardDivider
import dev.skyloft.skypython.ui.theme.CardGroup
import dev.skyloft.skypython.ui.theme.Chevron
import dev.skyloft.skypython.ui.theme.ChipTone
import dev.skyloft.skypython.ui.theme.ErrorRed
import dev.skyloft.skypython.ui.theme.ErrorSurface
import dev.skyloft.skypython.ui.theme.IconTile
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.OnErrorSurface
import dev.skyloft.skypython.ui.theme.Orange
import dev.skyloft.skypython.ui.theme.OrangeSoft
import dev.skyloft.skypython.ui.theme.SectionLabel
import dev.skyloft.skypython.ui.theme.SkyAlertDialog
import dev.skyloft.skypython.ui.theme.SkyCard
import dev.skyloft.skypython.ui.theme.SkyTopBar
import dev.skyloft.skypython.ui.theme.SlateLine
import dev.skyloft.skypython.ui.theme.StatusChip
import dev.skyloft.skypython.ui.theme.TextBright
import dev.skyloft.skypython.ui.theme.brandButtonColors
import dev.skyloft.skypython.ui.theme.brandSwitchColors
import java.util.Locale

const val UPDATE_BANNER_TAG = "update-banner"
const val UPDATE_INSTALL_TAG = "update-install"

/** The small orange dot of "an update is waiting" (☰, the drawer's About, About's row). */
@Composable
fun UpdateDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(Orange, CircleShape))
}

/** "SkyPython 0.0.6 is available · What's new · Update" over the editor; ✕ hides it for that version. */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onWhatsNew: () -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val version = state.available ?: return
    SkyCard(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag(UPDATE_BANNER_TAG),
        contentPadding = 0.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(Icons.Outlined.SystemUpdate, size = 36.dp, accent = true)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.update_banner_title, version.toString()),
                    color = TextBright,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(R.string.update_banner_text), color = MutedText, fontSize = 12.sp)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.update_banner_hide), tint = MutedText)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onWhatsNew) { Text(stringResource(R.string.update_whats_new), color = MutedText) }
            TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_banner_update), color = OrangeSoft, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** The Update screen: the new version, its notes, a big Install button and the download's progress. */
@Composable
fun UpdateScreen(controller: UpdateController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { SkyTopBar(title = stringResource(R.string.title_update), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val version = state.available
            if (version == null) {
                UpToDateCard(state, onCheck = controller::checkNow)
                return@Column
            }
            SkyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandText("SkyPython $version", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    StatusChip(stringResource(R.string.update_new), tone = ChipTone.Accent)
                }
                Text(
                    stringResource(R.string.update_installed_version, state.currentVersion),
                    color = MutedText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
                InstallArea(state, controller)
            }
            SectionLabel(stringResource(R.string.update_whats_new), Modifier.padding(start = 4.dp))
            SkyCard(Modifier.fillMaxWidth()) {
                val blocks = remember(state.notes) { state.notes?.let(ReleaseNotes::parse).orEmpty() }
                if (blocks.isEmpty()) {
                    Text(stringResource(R.string.update_notes_unavailable), color = MutedText, fontSize = 14.sp)
                } else {
                    Notes(blocks)
                }
                TextButton(
                    onClick = { openUrl(context, Releases.pageUrl(version)) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.update_open_github))
                }
            }
        }
    }
    if (state.needsInstallPermission) {
        SkyAlertDialog(
            onDismissRequest = controller::dismissPermission,
            icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = OrangeSoft) },
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(SystemApkInstaller.unknownSourcesIntent(context))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.update_permission_text, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.update_permission_open)) }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissPermission) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun InstallArea(state: UpdateState, controller: UpdateController) {
    when (val phase = state.phase) {
        is UpdatePhase.Downloading -> {
            Text(stringResource(R.string.update_downloading), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            val fraction = if (phase.total > 0) (phase.done.toFloat() / phase.total).coerceIn(0f, 1f) else null
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = Orange, trackColor = SlateLine)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Orange, trackColor = SlateLine)
            }
            Text(
                if (fraction != null) {
                    stringResource(R.string.update_progress, megabytes(phase.done), megabytes(phase.total), (fraction * 100).toInt())
                } else {
                    stringResource(R.string.update_progress_unknown, megabytes(phase.done))
                },
                color = MutedText,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = controller::cancel, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        UpdatePhase.Installing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Orange, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.update_installing), fontWeight = FontWeight.Medium)
            }
            Text(
                stringResource(R.string.update_installing_hint),
                color = MutedText,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        is UpdatePhase.Failed -> {
            FailureCard(phase.failure)
            Spacer(Modifier.height(12.dp))
            InstallButton(R.string.update_retry, controller::install)
        }
        UpdatePhase.Idle -> {
            InstallButton(R.string.update_install, controller::install)
            Text(
                stringResource(R.string.update_install_hint, variantLabel()),
                color = MutedText,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (!state.busy) {
        TextButton(onClick = controller::skip, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.update_skip), color = MutedText)
        }
    }
}

@Composable
private fun InstallButton(label: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = brandButtonColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UPDATE_INSTALL_TAG),
    ) {
        Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FailureCard(failure: UpdateFailure) {
    val text = when (failure) {
        UpdateFailure.Network -> stringResource(R.string.update_failed_network)
        UpdateFailure.Http -> stringResource(R.string.update_failed_http)
        UpdateFailure.NoChecksum -> stringResource(R.string.update_failed_no_checksum)
        UpdateFailure.Checksum -> stringResource(R.string.update_failed_checksum)
        UpdateFailure.Storage -> stringResource(R.string.update_failed_storage)
        is UpdateFailure.Install ->
            if (failure.incompatible) stringResource(R.string.update_failed_incompatible)
            else stringResource(R.string.update_failed_install, failure.message ?: "status ${failure.status}")
    }
    SkyCard(Modifier.fillMaxWidth(), color = ErrorSurface, border = ErrorRed.copy(alpha = 0.4f), contentPadding = 12.dp) {
        Row {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(20.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.update_failed_title), color = OnErrorSurface, fontWeight = FontWeight.SemiBold)
                Text(text, color = OnErrorSurface, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun UpToDateCard(state: UpdateState, onCheck: () -> Unit) {
    SkyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.Update, size = 40.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.update_none_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.about_version, state.currentVersion), color = MutedText, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onCheck,
            enabled = !state.checking,
            colors = brandButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.checking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MutedText)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (state.checking) R.string.update_checking else R.string.update_check))
        }
    }
}

@Composable
private fun Notes(blocks: List<NoteBlock>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Heading -> Text(
                    block.text,
                    color = if (block.level <= 2) TextBright else OrangeSoft,
                    fontSize = if (block.level <= 1) 18.sp else if (block.level == 2) 16.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp),
                )
                is NoteBlock.Bullet -> Row {
                    Text(block.marker, color = OrangeSoft, fontSize = 14.sp, modifier = Modifier.width(20.dp))
                    Text(block.text, fontSize = 14.sp, color = TextBright)
                }
                is NoteBlock.Paragraph -> Text(block.text, fontSize = 14.sp, color = TextBright)
            }
        }
    }
}

/**
 * Settings' "Updates" card: the automatic-check switch and "Check for updates" (a dot when one
 * is waiting). [onOpen] shows the Update screen.
 */
@Composable
fun UpdateSettingsSection(controller: UpdateController, onOpen: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    SectionLabel(
        stringResource(R.string.settings_section_updates),
        Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
    CardGroup {
        CheckRow(state) { if (state.badge) onOpen() else controller.checkNow() }
        CardDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { controller.setAutoCheck(!state.autoCheck) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconTile(Icons.Outlined.Update, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_auto_update), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_auto_update_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.autoCheck, onCheckedChange = controller::setAutoCheck, colors = brandSwitchColors())
        }
    }
}

/** About's "Check for updates" card. */
@Composable
fun UpdateAboutSection(controller: UpdateController, onOpen: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    CardGroup {
        CheckRow(state) { if (state.badge) onOpen() else controller.checkNow() }
    }
}

/** "Check for updates" — or "Version X is available" with a dot, which opens the Update screen. */
@Composable
private fun CheckRow(state: UpdateState, onClick: () -> Unit) {
    val available = state.available?.takeIf { state.badge }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !state.checking, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconTile(Icons.Outlined.SystemUpdate, size = 40.dp, accent = available != null)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (state.checking) R.string.update_checking else R.string.update_check),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (available != null) stringResource(R.string.update_check_summary_available, available.toString())
                else stringResource(R.string.about_version, state.currentVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = if (available != null) OrangeSoft else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            state.checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Orange)
            available != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                UpdateDot()
                Spacer(Modifier.width(8.dp))
                Chevron()
            }
        }
    }
}

/** "arm64-v8a" / "universal" (translated). */
@Composable
private fun variantLabel(): String =
    if (BuildConfig.SKY_APK_VARIANT == "universal") stringResource(R.string.update_variant_universal) else BuildConfig.SKY_APK_VARIANT

@Composable
private fun megabytes(bytes: Long): String =
    stringResource(R.string.update_megabytes, String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0))

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.about_no_app_for_link, url), Toast.LENGTH_SHORT).show()
    }
}
