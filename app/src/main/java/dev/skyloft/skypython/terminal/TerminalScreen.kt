package dev.skyloft.skypython.terminal

import dev.skyloft.skypython.ui.theme.SkyTheme
import androidx.compose.foundation.BorderStroke
import dev.skyloft.skypython.ui.theme.DarkSkyColors
import dev.skyloft.skypython.ui.theme.LightSkyColors
import dev.skyloft.skypython.ui.rememberMainScope
import dev.skyloft.skypython.ui.keepsSnackbarsAbove
import dev.skyloft.skypython.ui.theme.CodeFont
import dev.skyloft.skypython.settings.TerminalTheme
import dev.skyloft.skypython.settings.EditorSettings
import dev.skyloft.skypython.editor.codeTypeface
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.Image
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.pip.MissingPackage
import dev.skyloft.skypython.pip.PipOperation
import dev.skyloft.skypython.pip.PipViewModel
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.skyloft.skypython.R
import dev.skyloft.skypython.console.InstallError
import dev.skyloft.skypython.console.Installing
import dev.skyloft.skypython.console.InterruptButton
import dev.skyloft.skypython.report.AppLog
import dev.skyloft.skypython.ui.theme.SkyTopBar
import kotlinx.coroutines.launch
import java.io.File
import dev.skyloft.skypython.ui.theme.brandButtonColors
import kotlin.math.roundToInt

/** Test tag of the terminal; its semantics text is what is on the screen. */
const val TERMINAL_TAG = "terminal"

/**
 * A real terminal (VT100/xterm emulator on a pseudo-terminal) for a script (▶ Run), the
 * interpreter or the shell, with a key row for the keys a phone keyboard lacks and the output
 * actions: copy, share, save, clear, scroll, text size. Long-press selects text.
 *
 * @param onUseSimpleConsole fallback when the pseudo-terminal cannot be created.
 * @param onOpenInEditor a traceback's `File "…", line N` was tapped.
 * @param settings text size, colours and typeface (Settings → Terminal); the interpreter also
 *   gets a command line ("Type a command…" and an orange send button).
 * @param onFontSizeChange the user zoomed (pinch or menu) to this size (sp); persist it.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
    onUseSimpleConsole: () -> Unit,
    onOpenInEditor: (File, Int) -> Unit = { _, _ -> },
    settings: EditorSettings = EditorSettings(),
    onFontSizeChange: (Int) -> Unit = {},
    /** The app's pip, for "Install x" after a run that stopped on a missing module (Run only). */
    pip: PipViewModel? = null,
    /** A picture the finished script saved was tapped (Run only). */
    onOpenImage: (File) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var installing by remember { mutableStateOf<MissingPackage?>(null) }
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val kind = viewModel.kind
    val actions = state.actions(kind)
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val scheme = terminalScheme(settings.terminalTheme)
    val background = scheme.background
    LaunchedEffect(settings.terminalFontSizeSp) { viewModel.setFontSize(settings.terminalFontSizeSp) }
    val saveFont by rememberUpdatedState(onFontSizeChange)
    DisposableEffect(viewModel) {
        viewModel.onUserZoom = { saveFont(it) }
        onDispose { viewModel.onUserZoom = null }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.saveOutputTo(uri)
    }

    val currentOpenInEditor by rememberUpdatedState(onOpenInEditor)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { pending ->
            for (event in pending) {
                viewModel.consumed(event)
                when (event) {
                    // Not awaited: the next event must not wait for the snackbar to go.
                    is TerminalEvent.Message -> launch { snackbar.showSnackbar(event.text) }
                    is TerminalEvent.Share -> shareOutput(context, event)
                    is TerminalEvent.OpenInEditor -> currentOpenInEditor(event.file, event.line)
                }
            }
        }
    }

    // Shown after the process was restarted (a fresh view model): the interpreter / shell start
    // here, not only from the click that opened the screen; ▶ Run has no script left, so it
    // goes back to the editor.
    val body = TerminalBody.of(state, kind)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) { viewModel.onShown() }
    LaunchedEffect(body) { if (body == TerminalBody.NoScript) back() }

    // A finished script reads no input: put the keyboard away so the footer's actions show.
    LaunchedEffect(state.running, view) {
        if (!state.running && kind == TerminalKind.Run) view?.let(::hideKeyboard)
    }

    val scopeForMessages = rememberMainScope()
    val sheetFor = installing
    if (sheetFor != null && pip != null) {
        InstallMissingSheet(
            missing = sheetFor,
            pip = pip,
            onRunAgain = {
                installing = null
                viewModel.restart()
            },
            onDismiss = { installing = null },
        )
    }

    Scaffold(
        containerColor = background,
        contentColor = colors.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SkyTopBar(
                title = viewModel.title,
                onBack = onBack,
                actions = {
                    TopActions(viewModel, actions, state, onSaveAs = { saveAs.launch(viewModel.suggestedOutputName()) })
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                // The terminal shrinks above the keyboard; the program is told its new size.
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val session = state.session
                when (body) {
                    TerminalBody.InstallError -> InstallError(state.installError.orEmpty(), viewModel::restart)
                    TerminalBody.StartError -> StartError(state.startError.orEmpty(), kind, onUseSimpleConsole)
                    TerminalBody.Terminal -> if (session != null) {
                        TerminalPane(session, viewModel, state.fontSp, settings, scheme) { view = it }
                    }
                    TerminalBody.Installing -> Installing()
                    TerminalBody.Starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    TerminalBody.NoScript -> Unit
                }
                if (!state.atBottom && state.session != null) {
                    SmallFloatingActionButton(
                        onClick = viewModel::scrollToBottom,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.output_scroll_bottom))
                    }
                }
            }
            if (kind == TerminalKind.Run && !state.running && state.exitCode != null) {
                val missing = state.missing
                if (missing != null && pip != null) {
                    InstallMissingRow(missing, enabled = installing == null) {
                        if (pip.installNow(missing.pipName) || pip.state.value.running == PipOperation.Install(missing.pipName)) {
                            installing = missing
                        } else {
                            launchMessage(snackbar, scopeForMessages, AppLocale.string(context, R.string.run_install_busy))
                        }
                    }
                }
                if (state.images.isNotEmpty()) RunImagesRow(state.images, onOpenImage)
                FinishedBar(
                    exitCode = state.exitCode!!,
                    onRunAgain = viewModel::restart,
                    onBackToEditor = onBack,
                )
            } else {
                if (kind == TerminalKind.Interpreter && state.session != null) {
                    CommandLine(enabled = state.running, onSend = viewModel::sendLine)
                }
                KeyRow(
                    modifiers = state.modifiers,
                    enabled = state.running,
                    onKey = viewModel::onExtraKey,
                )
            }
        }
    }
}

/** ^C and Stop while running, Run again / Restart after; Copy all; the rest in ⋮. */
@Composable
private fun TopActions(
    viewModel: TerminalViewModel,
    actions: OutputActions,
    state: TerminalState,
    onSaveAs: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (actions.canInterrupt) {
        InterruptButton(onClick = viewModel::interrupt)
        IconButton(onClick = viewModel::kill) {
            Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.action_stop), tint = colors.error)
        }
    } else if (actions.canRunAgain) {
        IconButton(onClick = viewModel::restart) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.run_again))
        }
    } else if (actions.canRestart && (state.session != null || state.startError != null)) {
        IconButton(onClick = viewModel::restart) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_restart))
        }
    }
    IconButton(onClick = viewModel::copyAll, enabled = actions.canCopy) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.output_copy_all))
    }
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.output_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item = @Composable { label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    enabled = enabled,
                    onClick = { open = false; onClick() },
                )
            }
            item(R.string.output_copy_all, Icons.Outlined.ContentCopy, actions.canCopy) { viewModel.copyAll() }
            item(R.string.output_share, Icons.Outlined.Share, actions.canShare) { viewModel.share() }
            item(R.string.output_save, Icons.Outlined.Save, actions.canSave) { viewModel.saveOutput() }
            item(R.string.output_save_as, Icons.Outlined.SaveAs, actions.canSave) { onSaveAs() }
            item(R.string.output_clear, Icons.Outlined.DeleteSweep, actions.canClear) { viewModel.clear() }
            HorizontalDivider()
            item(R.string.output_scroll_top, Icons.Outlined.VerticalAlignTop, state.session != null) { viewModel.scrollToTop() }
            item(R.string.output_scroll_bottom, Icons.Outlined.VerticalAlignBottom, state.session != null) { viewModel.scrollToBottom() }
            HorizontalDivider()
            // Text size: − and + side by side, the size between them; the menu stays open.
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { viewModel.zoom(bigger = false) },
                    enabled = state.fontSp > TerminalState.MIN_FONT_SP,
                ) {
                    Icon(Icons.Outlined.TextDecrease, contentDescription = stringResource(R.string.output_font_smaller))
                }
                Text(
                    stringResource(R.string.output_font_size, state.fontSp.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(min = 72.dp),
                )
                IconButton(
                    onClick = { viewModel.zoom(bigger = true) },
                    enabled = state.fontSp < TerminalState.MAX_FONT_SP,
                ) {
                    Icon(Icons.Outlined.TextIncrease, contentDescription = stringResource(R.string.output_font_bigger))
                }
            }
        }
    }
}

private fun launchMessage(snackbar: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope, text: String) {
    scope.launch { snackbar.showSnackbar(text) }
}

/** Test tag of each picture chip above the finished bar. */
const val RUN_IMAGE_CHIP_TAG = "run_image_chip"

/** Above the finished bar: the pictures the script saved; a tap shows one in the viewer. */
@Composable
private fun RunImagesRow(images: List<File>, onOpen: (File) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SkyTheme.colors.bar)
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.run_images_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (image in images) {
            val description = stringResource(R.string.run_image_open, image.name)
            AssistChip(
                onClick = { onOpen(image) },
                label = { Text(image.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .testTag(RUN_IMAGE_CHIP_TAG)
                    .semantics { contentDescription = description },
            )
        }
    }
}

/** Under a finished script: how it ended, Run again, Back to editor. */
@Composable
private fun FinishedBar(exitCode: Int, onRunAgain: () -> Unit, onBackToEditor: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .keepsSnackbarsAbove()
            .fillMaxWidth()
            .background(SkyTheme.colors.bar)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.run_exit_code, exitCode),
            style = MaterialTheme.typography.labelLarge,
            color = if (exitCode == 0) colors.onSurfaceVariant else colors.error,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onBackToEditor, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.back_to_editor))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRunAgain, colors = brandButtonColors(), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.run_again))
        }
    }
}

/** The emulator view; survives recomposition, re-attaches to a new session after Restart. */
@Composable
private fun TerminalPane(
    session: TerminalSession,
    viewModel: TerminalViewModel,
    fontSp: Float,
    settings: EditorSettings,
    scheme: TerminalScheme,
    onView: (TerminalView?) -> Unit,
) {
    val palette = TerminalPalette(
        foreground = scheme.foreground.toArgb(),
        background = scheme.background.toArgb(),
        cursor = scheme.cursor.toArgb(),
        light = scheme.light,
    )
    val context = LocalContext.current
    val typeface = remember(settings.codeFont) { codeTypeface(context, settings.codeFont) }
    val fontPx = with(LocalDensity.current) { fontSp.sp.toPx() }.roundToInt()
    // The palette and session last given to the view: the update below runs on every
    // recomposition, and re-applying the colours means a full redraw.
    val applied = remember { arrayOfNulls<Any>(2) }

    Box(Modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            // A little air between the text and the screen edge.
            .padding(start = 6.dp),
        factory = { context ->
            TerminalView(context, null).apply {
                palette.applyDefaults()
                setTerminalViewClient(ViewClient(this, viewModel))
                isFocusable = true
                isFocusableInTouchMode = true
                // The renderer is created by setTextSize; setTypeface needs it.
                setTextSize(fontPx)
                setTypeface(typeface)
                tag = fontPx to typeface
                applied[0] = palette
                attachSession(session)
                viewModel.attachView(this)
                onView(this)
                if (!viewModel.isInterpreter) {
                    post {
                        requestFocus()
                        if (viewModel.state.value.running) showKeyboard(this)
                    }
                }
            }
        },
        update = { view ->
            // Colours may have changed (Settings → Terminal → Theme), or Restart made a new session.
            val recolour = applied[0] != palette || applied[1] !== session
            if (recolour) {
                palette.applyDefaults()
                palette.applyTo(session)
                applied[0] = palette
                applied[1] = session
            }
            if (view.tag != (fontPx to typeface)) {
                view.tag = fontPx to typeface
                view.setTextSize(fontPx)
                view.setTypeface(typeface)
            }
            if (view.currentSession !== session) {
                view.stopTextSelectionMode()
                view.attachSession(session)
                view.requestFocus()
            }
            if (recolour) view.invalidate()
            viewModel.attachView(view)
        },
        onRelease = { view ->
            viewModel.detachView(view)
            onView(null)
        },
    )
    ScreenTextSemantics(viewModel, Modifier.matchParentSize())
    }
}

/**
 * The text on screen as the terminal's accessibility text (and what UI tests read). Its own
 * composable: the text changes up to 10 times a second and only this node recomposes for it,
 * not the terminal view.
 */
@Composable
private fun ScreenTextSemantics(viewModel: TerminalViewModel, modifier: Modifier) {
    val screenText by viewModel.screenText.collectAsStateWithLifecycle()
    Box(modifier.testTag(TERMINAL_TAG).semantics { text = AnnotatedString(screenText) })
}

private fun showKeyboard(view: TerminalView) {
    val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return
    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

private fun hideKeyboard(view: TerminalView) {
    val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}

/** Share output: plain text, or a text file through the FileProvider when it is long. */
private fun shareOutput(context: Context, event: TerminalEvent.Share) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, event.title)
        if (event.file != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", event.file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            putExtra(Intent.EXTRA_TEXT, event.text)
        }
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.output_share)).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
    }
}
/**
 * The 16 ANSI colours, tuned to the near-black console and the orange accent (the stock xterm
 * ones are pure, saturated primaries). Order: black, red, green, yellow, blue, magenta, cyan,
 * white, then the bright eight. Magenta is the accent orange: Python 3.13+ paints its `>>>`
 * prompt with it.
 */
private val DARK_ANSI_COLORS = longArrayOf(
    0xFF3C3E43, 0xFFF0726A, 0xFF7EC27F, 0xFFF0C26B, 0xFF56A8F5, 0xFFF5A962, 0xFF3FBFC9, 0xFFC7C9CF,
    0xFF6F737A, 0xFFFF9B94, 0xFFA3D9A0, 0xFFFFD48F, 0xFF8CC4FA, 0xFFF7B474, 0xFF7ED8DF, 0xFFF2F3F5,
).let { argb -> IntArray(argb.size) { argb[it].toInt() } }

/**
 * The light console's 16 colours: deep, dark-on-light inks (≥ 4.5:1 on the console for the
 * normal eight but white), so coloured output stays readable. "White" is a mid grey, not
 * invisible white-on-white; magenta is the burnt accent orange of the light theme.
 */
private val LIGHT_ANSI_COLORS = longArrayOf(
    0xFF24292F, 0xFFC42B1C, 0xFF1A7F37, 0xFF8A6100, 0xFF0B5CC4, 0xFFB05400, 0xFF0A7C86, 0xFF6E7781,
    0xFF57606A, 0xFFCF222E, 0xFF1F883D, 0xFF9A6700, 0xFF0969DA, 0xFFC25E0A, 0xFF1B7C83, 0xFF8C959F,
).let { argb -> IntArray(argb.size) { argb[it].toInt() } }

/**
 * A terminal theme resolved against the app theme: [background], default [foreground] text,
 * [cursor], and whether the [light] ANSI colours apply.
 */
private data class TerminalScheme(val background: Color, val foreground: Color, val cursor: Color, val light: Boolean)

/** [theme] for the current app palette: Auto follows the app (light console in the light theme). */
@Composable
private fun terminalScheme(theme: TerminalTheme): TerminalScheme {
    val app = SkyTheme.colors
    val light = theme.isLight(app.isLight)
    val c = if (light) LightSkyColors else DarkSkyColors
    return TerminalScheme(
        background = if (theme == TerminalTheme.Black) TERMINAL_BLACK else c.console,
        foreground = c.consoleText,
        cursor = if (light) c.accentText else c.accent,
        light = light,
    )
}

/** The "Black" theme: near-black for OLED screens at night. */
private val TERMINAL_BLACK = Color(0xFF0A0A0A)

/** The terminal's default foreground, background and cursor, and its ANSI colours. */
private data class TerminalPalette(val foreground: Int, val background: Int, val cursor: Int, val light: Boolean) {
    private val ansi get() = if (light) LIGHT_ANSI_COLORS else DARK_ANSI_COLORS

    /** For emulators created from now on, and for a full reset (`ESC c`). */
    fun applyDefaults() {
        val d = TerminalColors.COLOR_SCHEME.mDefaultColors
        ansi.copyInto(d)
        d[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
        d[TextStyle.COLOR_INDEX_BACKGROUND] = background
        d[TextStyle.COLOR_INDEX_CURSOR] = cursor
    }

    /** For an emulator that already exists (colours a program set itself are kept). */
    fun applyTo(session: TerminalSession) {
        val c = session.emulator?.mColors?.mCurrentColors ?: return
        ansi.copyInto(c)
        c[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
        c[TextStyle.COLOR_INDEX_BACKGROUND] = background
        c[TextStyle.COLOR_INDEX_CURSOR] = cursor
    }
}

/** Keyboard, touch, selection and zoom handling for the terminal view. */
private class ViewClient(
    private val view: TerminalView,
    private val viewModel: TerminalViewModel,
) : TerminalViewClient {

    private val running get() = viewModel.state.value.running

    override fun onScale(scale: Float): Float {
        // Pinch: one font step per 10 % of scaling.
        if (scale < 0.9f || scale > 1.1f) {
            viewModel.zoom(bigger = scale > 1f)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        // A traceback's `File "…", line N` opens the editor there; elsewhere a tap brings up the
        // keyboard while the program can read it.
        if (e != null && viewModel.openTracebackAt(view.getColumnAndRow(e, true)[1])) return
        if (running) showKeyboard(view)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit

    /** "[Process completed - press Enter]" of the interpreter and the shell: Enter starts it again. */
    private fun restartsOnEnter(session: TerminalSession?) =
        viewModel.kind != TerminalKind.Run && session != null && !session.isRunning

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && restartsOnEnter(session)) {
            viewModel.restart()
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false

    override fun readControlKey() = viewModel.consumeCtrl()
    override fun readAltKey() = viewModel.consumeAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if ((codePoint == '\r'.code || codePoint == '\n'.code) && restartsOnEnter(session)) {
            viewModel.restart()
            return true
        }
        return false
    }

    override fun onEmulatorSet() {
        view.invalidate()
    }

    // Selection menu.
    override fun canPaste() = running
    override fun onCopyAll() = viewModel.copyAll()
    override fun onShareText(text: String?) = viewModel.share(text.orEmpty())
    override fun onScrolledToBottomChanged(atBottom: Boolean) = viewModel.onScrolledToBottomChanged(atBottom)

    override fun logError(tag: String?, message: String?) {
        AppLog.e(tag ?: "TerminalView", message.orEmpty())
    }

    override fun logWarn(tag: String?, message: String?) {
        AppLog.w(tag ?: "TerminalView", message.orEmpty())
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag, message.orEmpty())
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag, message.orEmpty())
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag, message.orEmpty())
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        AppLog.e(tag ?: "TerminalView", message.orEmpty(), e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        AppLog.e(tag ?: "TerminalView", "", e)
    }
}

/** ESC TAB CTRL ALT - / | ← ↑ ↓ →, scrollable on narrow screens; armed modifiers are highlighted. */
@Composable
private fun KeyRow(modifiers: Modifiers, enabled: Boolean, onKey: (ExtraKey) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .keepsSnackbarsAbove()
            .fillMaxWidth()
            .height(44.dp)
            .background(SkyTheme.colors.bar)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in ExtraKey.ROW) {
            val description = stringResource(key.descriptionRes)
            val armed = (key == ExtraKey.Ctrl && modifiers.ctrl) || (key == ExtraKey.Alt && modifiers.alt)
            TextButton(
                onClick = { onKey(key) },
                enabled = enabled,
                // An armed Ctrl / Alt sits on a tonal pill, so it reads as "on" at a glance.
                // Every key sits on a soft keycap one step above the bar.
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (armed) SkyTheme.colors.accentTint else SkyTheme.colors.panel,
                    disabledContainerColor = SkyTheme.colors.panel.copy(alpha = 0.5f),
                ),
                border = BorderStroke(1.dp, if (armed) SkyTheme.colors.accent.copy(alpha = 0.45f) else SkyTheme.colors.line),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .height(34.dp)
                    .widthIn(min = 44.dp)
                    .semantics { contentDescription = description },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    key.label,
                    fontFamily = CodeFont,
                    fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp,
                    color = when {
                        !enabled -> colors.onSurface.copy(alpha = 0.38f)
                        armed -> SkyTheme.colors.accentText
                        else -> colors.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun StartError(message: String, kind: TerminalKind, onUseSimpleConsole: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.terminal_open_failed), style = MaterialTheme.typography.titleMedium)
        Text(message, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error)
        Button(onClick = onUseSimpleConsole, colors = brandButtonColors()) {
            Text(stringResource(if (kind == TerminalKind.Run) R.string.use_simple_output else R.string.terminal_use_simple_console))
        }
    }
}


/**
 * The interpreter's command line: a rounded field and a round orange send button. Enter sends
 * too; an empty send is a plain Enter (handy after ↑ recalled a line in the terminal).
 */
@Composable
private fun CommandLine(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(enabled) { if (enabled) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .keepsSnackbarsAbove()
            .fillMaxWidth()
            .background(SkyTheme.colors.bar)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = SkyTheme.colors.text, fontFamily = CodeFont, fontSize = 15.sp),
            cursorBrush = SolidColor(SkyTheme.colors.accent),
            // Code, not prose: no auto-capitals ("i" → "I") and no autocorrect.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .background(SkyTheme.colors.panel, shape)
                        .border(1.dp, SkyTheme.colors.line, shape)
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(stringResource(R.string.terminal_command_hint), color = SkyTheme.colors.mutedText, fontSize = 15.sp)
                    }
                    inner()
                }
            },
        )
        val sendLabel = stringResource(R.string.action_send)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(if (enabled) SkyTheme.colors.accent else SkyTheme.colors.raised)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = sendLabel) { send() }
                .semantics { contentDescription = sendLabel },
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = null,
                tint = if (enabled) SkyTheme.colors.onAccent else SkyTheme.colors.mutedText,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
