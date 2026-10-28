package dev.skyloft.skypython.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.files.describeIoError
import dev.skyloft.skypython.share.openWith
import dev.skyloft.skypython.share.shareFile
import dev.skyloft.skypython.terminal.RunPlots
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.SkyTheme
import dev.skyloft.skypython.ui.theme.skyTopBarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Test tag of the plot pager. */
const val PLOT_PAGER_TAG = "plot_pager"

/**
 * The figures a program showed with `plt.show()`, like windows on a computer: swipe between them,
 * pinch to zoom; Save to project, Share, Open with…. Back returns to the program's output.
 *
 * @param plots the session's figures, oldest first (it grows while the program runs).
 * @param jumpTo new figures arrived while this is open: move to this page, then [onJumped].
 * @param save copies a figure into the project; returns the new file or the error.
 * @param displayPath how a saved file is named in the confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlotViewerScreen(
    plots: List<File>,
    initialPage: Int,
    jumpTo: Int?,
    onJumped: () -> Unit,
    save: suspend (File) -> Result<File>,
    displayPath: (File) -> String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, (plots.size - 1).coerceAtLeast(0))) { plots.size }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(jumpTo) {
        val page = jumpTo ?: return@LaunchedEffect
        onJumped()
        if (page in plots.indices) pager.animateScrollToPage(page)
    }
    val current = plots.getOrNull(pager.currentPage)

    Scaffold(
        containerColor = SkyTheme.colors.console,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (current == null) "" else stringResource(R.string.plot_figure, RunPlots.label(current)),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (plots.size > 1) {
                                Text(
                                    stringResource(R.string.plot_position, pager.currentPage + 1, plots.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SkyTheme.colors.mutedText,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (current != null) {
                            IconButton(onClick = {
                                scope.launch {
                                    val text = save(current).fold(
                                        { AppLocale.string(context, R.string.output_saved, displayPath(it)) },
                                        { e -> AppLocale.string(context, R.string.plot_save_failed, describeIoError(e) { AppLocale.string(context, it) }) },
                                    )
                                    snackbar.showSnackbar(text)
                                }
                            }) {
                                Icon(Icons.Outlined.SaveAlt, contentDescription = stringResource(R.string.plot_save))
                            }
                            IconButton(onClick = { shareFile(context, current) }) {
                                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.title_share))
                            }
                            IconButton(onClick = { openWith(context, current) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.open_with))
                            }
                        }
                    },
                    colors = skyTopBarColors(),
                )
                Hairline()
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.padding(padding).fillMaxSize().testTag(PLOT_PAGER_TAG),
            key = { plots[it].path },
        ) { page ->
            PlotPage(plots[page])
        }
    }
}

@Composable
private fun PlotPage(file: File) {
    val context = LocalContext.current
    val loaded by produceState<LoadedImage?>(null, file) {
        value = withContext(Dispatchers.IO) { ImageLoader.load(context.applicationContext, file) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val image = loaded) {
            null -> CircularProgressIndicator()
            is LoadedImage.Ok -> ZoomableImage(image.bitmap, file.name)
            is LoadedImage.Failed -> ViewerError(image)
        }
    }
}
