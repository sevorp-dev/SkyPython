package dev.skyloft.skypython.pip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.skyloft.skypython.ui.theme.SkyTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skyloft.skypython.ui.theme.BarNavy
import dev.skyloft.skypython.ui.theme.DividerNavy
import dev.skyloft.skypython.ui.theme.ErrorRed
import dev.skyloft.skypython.ui.theme.GlowLine
import dev.skyloft.skypython.ui.theme.brandButtonColors
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.NightNavy
import dev.skyloft.skypython.ui.theme.SuccessGreen
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R

private val LogBackground = NightNavy
private val LogText = Color(0xFFDCE3F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipScreen(viewModel: PipViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            SkyTopBar(
                title = stringResource(R.string.title_pip),
                onBack = onBack,
                glow = false,
                actions = {
                    if (state.tab == PipTab.Installed) {
                        IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            PrimaryTabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = BarNavy,
                contentColor = Color.White,
                divider = {},
            ) {
                PipTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(stringResource(tab.labelRes()), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selectedContentColor = Color.White,
                        unselectedContentColor = MutedText,
                    )
                }
            }
            GlowLine()
            when (state.tab) {
                PipTab.Install -> InstallTab(state, viewModel)
                PipTab.Installed -> InstalledTab(state, viewModel)
            }
        }
    }

    state.confirmUninstall?.let { pkg ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUninstall,
            title = { Text(stringResource(R.string.pip_uninstall_title, pkg.name)) },
            text = { Text(stringResource(R.string.pip_uninstall_text, pkg.version)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUninstall) { Text(stringResource(R.string.pip_uninstall), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUninstall) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun InstallTab(state: PipState, viewModel: PipViewModel) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = state.spec,
                onValueChange = viewModel::onSpecChange,
                modifier = Modifier.weight(1f),
                enabled = !state.installing,
                singleLine = true,
                label = { Text(stringResource(R.string.pip_package)) },
                placeholder = { Text(stringResource(R.string.pip_package_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                isError = state.specError != null,
                supportingText = state.specError?.let { { Text(stringResource(it.messageRes())) } },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(6.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.install() }),
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = viewModel::install,
                enabled = !state.installing,
                shape = RoundedCornerShape(6.dp),
                colors = brandButtonColors(),
                // The outlined field reserves 8dp above its box for the floating label.
                modifier = Modifier.padding(top = 8.dp).height(56.dp),
            ) { Text(stringResource(R.string.pip_install)) }
        }
        if (state.installing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }
        when (val outcome = state.outcome) {
            is InstallOutcome.Succeeded -> StatusText(stringResource(R.string.pip_installed_spec, outcome.spec), SuccessGreen)
            is InstallOutcome.Failed -> {
                StatusText(stringResource(R.string.pip_install_failed), ErrorRed)
                if (outcome.buildFailure) StatusText(stringResource(R.string.pip_pure_python_hint), MaterialTheme.colorScheme.secondary)
            }
            null -> if (!state.installing && state.log.isEmpty()) {
                StatusText(
                    stringResource(R.string.pip_intro) + " " + stringResource(R.string.pip_pure_python_hint),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // An empty black box before the first install only looks broken.
        if (state.log.isNotEmpty() || state.installing) {
            Spacer(Modifier.height(8.dp))
            InstallLog(state.log, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun InstallLog(log: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
    Box(
        modifier
            .background(LogBackground, RoundedCornerShape(6.dp))
            .padding(8.dp)
            .verticalScroll(scroll),
    ) {
        SelectionContainer {
            Text(
                text = log,
                color = LogText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun InstalledTab(state: PipState, viewModel: PipViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.listError?.let {
            Text(
                it,
                color = ErrorRed,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (state.packages.isEmpty() && !state.loading && state.listError == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.pip_no_packages),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.packages, key = { it.name }) { pkg ->
                PackageRow(
                    pkg = pkg,
                    busy = state.uninstalling == pkg.name,
                    enabled = state.uninstalling == null,
                    onUninstall = { viewModel.requestUninstall(pkg) },
                )
                HorizontalDivider(color = DividerNavy)
            }
        }
    }
}

@Composable
private fun PackageRow(pkg: PipPackage, busy: Boolean, enabled: Boolean, onUninstall: () -> Unit) {
    Surface(color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(pkg.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pkg.version,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onUninstall, enabled = enabled) { Text(stringResource(R.string.pip_uninstall), color = ErrorRed) }
            }
        }
    }
}

@StringRes
private fun PipTab.labelRes(): Int = when (this) {
    PipTab.Install -> R.string.pip_tab_install
    PipTab.Installed -> R.string.pip_tab_installed
}

@StringRes
private fun SpecProblem.messageRes(): Int = when (this) {
    SpecProblem.Empty -> R.string.pip_spec_empty
    SpecProblem.Spaces -> R.string.pip_spec_spaces
    SpecProblem.Options -> R.string.pip_spec_options
    SpecProblem.Invalid -> R.string.pip_spec_invalid
}
