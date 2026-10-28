package dev.skyloft.skypython.viewer

import dev.skyloft.skypython.ui.theme.SkyTheme
import android.graphics.Bitmap
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Share
import dev.skyloft.skypython.ui.theme.SkyAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R
import dev.skyloft.skypython.share.openWith
import dev.skyloft.skypython.share.shareFile
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.skyTopBarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Test tag of the picture; its content description is the file name once it is shown. */
const val IMAGE_VIEWER_TAG = "image_viewer"

/** Test tag of the message shown when the file can't be shown. */
const val IMAGE_VIEWER_ERROR_TAG = "image_viewer_error"

private sealed interface ViewerState {
    data object Loading : ViewerState
    data class Ready(val image: LoadedImage.Ok) : ViewerState
    data class Error(val failure: LoadedImage.Failed) : ViewerState
}

/**
 * Shows the picture [file] (a plot a script saved, a photo in the projects): pinch and
 * double-tap to zoom, drag to pan, a dark checkerboard behind transparent parts; name, size and
 * dimensions on top, with Share, Open with… and Delete.
 *
 * @param onDeleted the file was deleted (the screen should close).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(file: File, onBack: () -> Unit, onDeleted: (File) -> Unit) {
    val context = LocalContext.current
    val state by produceState<ViewerState>(ViewerState.Loading, file) {
        val loaded = withContext(Dispatchers.IO) { ImageLoader.load(context.applicationContext, file) }
        value = when (loaded) {
            is LoadedImage.Ok -> ViewerState.Ready(loaded)
            is LoadedImage.Failed -> ViewerState.Error(loaded)
        }
    }
    val ready = (state as? ViewerState.Ready)?.image
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = SkyTheme.colors.console,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(file.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val size = Formatter.formatShortFileSize(context, ready?.bytes ?: file.length())
                            Text(
                                if (ready != null) stringResource(R.string.viewer_info, ready.width, ready.height, size) else size,
                                style = MaterialTheme.typography.bodySmall,
                                color = SkyTheme.colors.mutedText,
                                maxLines = 1,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { shareFile(context, file) }) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.title_share))
                        }
                        IconButton(onClick = { openWith(context, file) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.open_with))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    },
                    colors = skyTopBarColors(),
                )
                Hairline()
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val s = state) {
                ViewerState.Loading -> CircularProgressIndicator()
                is ViewerState.Ready -> ZoomableImage(s.image.bitmap, file.name)
                is ViewerState.Error -> ViewerError(s.failure)
            }
        }
    }

    if (confirmDelete) {
        SkyAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.files_delete_file_title)) },
            text = { Text(stringResource(R.string.files_delete_file_text, file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) { file.delete() || !file.exists() }
                        if (deleted) onDeleted(file)
                        else Toast.makeText(context, R.string.viewer_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ViewerError(failure: LoadedImage.Failed) {
    val text = when (failure.reason) {
        LoadedImage.Reason.Missing -> stringResource(R.string.viewer_error_missing)
        LoadedImage.Reason.Unsupported -> stringResource(R.string.viewer_error_unsupported)
        LoadedImage.Reason.TooBig -> stringResource(R.string.viewer_error_memory)
        LoadedImage.Reason.Unreadable -> stringResource(R.string.viewer_error_read, failure.detail.orEmpty())
    }
    Column(
        Modifier.padding(32.dp).testTag(IMAGE_VIEWER_ERROR_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = SkyTheme.colors.mutedText, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.viewer_error_title), style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SkyTheme.colors.mutedText, textAlign = TextAlign.Center)
    }
}

/** A checkerboard tile (2×2 cells of [cellPx]) in the two given colours, repeated as a brush. */
private fun checkerTile(cellPx: Int, dark: Color, light: Color): ImageBitmap {
    val size = cellPx * 2
    val bitmap = createBitmap(size, size)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint()
    paint.color = dark.toArgb()
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    paint.color = light.toArgb()
    canvas.drawRect(cellPx.toFloat(), 0f, size.toFloat(), cellPx.toFloat(), paint)
    canvas.drawRect(0f, cellPx.toFloat(), cellPx.toFloat(), size.toFloat(), paint)
    return bitmap.asImageBitmap()
}

/** The picture fitted to the view; pinch / double tap zoom (to [ImageMath.MAX_ZOOM]), drag pans. */
@Composable
private fun ZoomableImage(bitmap: Bitmap, name: String) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val cellPx = with(LocalDensity.current) { 8.dp.roundToPx() }.coerceAtLeast(2)
    val dark = SkyTheme.colors.panel
    val light = SkyTheme.colors.raised
    val checker = remember(cellPx, dark, light) {
        ShaderBrush(ImageShader(checkerTile(cellPx, dark, light), TileMode.Repeated, TileMode.Repeated))
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var viewSize by remember { mutableStateOf(Size.Zero) }
    val scope = rememberCoroutineScope()
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()

    fun fit() = ImageMath.fitScale(bw, bh, viewSize.width, viewSize.height)
    fun clamp() {
        val f = fit()
        panX = ImageMath.clampPan(panX, bw * f, zoom, viewSize.width)
        panY = ImageMath.clampPan(panY, bh * f, zoom, viewSize.height)
    }
    fun animateTo(targetZoom: Float, focus: Offset) {
        val z0 = zoom
        val x0 = panX
        val y0 = panY
        val f = fit()
        val x1 = if (targetZoom <= 1f) 0f else ImageMath.clampPan(ImageMath.panAfterZoom(x0, focus.x, z0, targetZoom), bw * f, targetZoom, viewSize.width)
        val y1 = if (targetZoom <= 1f) 0f else ImageMath.clampPan(ImageMath.panAfterZoom(y0, focus.y, z0, targetZoom), bh * f, targetZoom, viewSize.height)
        scope.launch {
            animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                zoom = z0 + (targetZoom - z0) * t
                panX = x0 + (x1 - x0) * t
                panY = y0 + (y1 - y0) * t
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .testTag(IMAGE_VIEWER_TAG)
                .semantics { contentDescription = name }
                .pointerInput(bitmap) {
                    detectTapGestures(onDoubleTap = { tap ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        animateTo(ImageMath.doubleTapZoom(zoom), tap - center)
                    })
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val focus = centroid - center
                        val newZoom = (zoom * gestureZoom).coerceIn(1f, ImageMath.MAX_ZOOM)
                        panX = ImageMath.panAfterZoom(panX, focus.x, zoom, newZoom) + pan.x
                        panY = ImageMath.panAfterZoom(panY, focus.y, zoom, newZoom) + pan.y
                        zoom = newZoom
                        clamp()
                    }
                },
        ) {
            val scale = ImageMath.fitScale(bw, bh, size.width, size.height) * zoom
            val w = bw * scale
            val h = bh * scale
            val left = (size.width - w) / 2f + panX
            val top = (size.height - h) / 2f + panY
            val visible = Rect(left, top, left + w, top + h).intersect(Rect(Offset.Zero, size))
            if (!visible.isEmpty) {
                clipRect(visible.left, visible.top, visible.right, visible.bottom) {
                    drawRect(checker, topLeft = visible.topLeft, size = visible.size)
                }
            }
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                // Zoomed far in, show the pixels as they are (pixel art, small plots).
                filterQuality = if (scale >= 3f) FilterQuality.None else FilterQuality.Low,
            )
        }
        if (zoom > 1.01f) {
            SmallFloatingActionButton(
                onClick = { animateTo(1f, Offset.Zero) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(Icons.Outlined.FitScreen, contentDescription = stringResource(R.string.viewer_fit))
            }
        }
    }
}
