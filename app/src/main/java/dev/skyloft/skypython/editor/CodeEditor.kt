package dev.skyloft.skypython.editor

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource

private const val EDITOR_BG = 0xFF0F1720.toInt()
private const val GUTTER_DIVIDER = 0xFF26323E.toInt()
private const val THEME_PATH = "textmate/skypython-dark.json"
private const val THEME_NAME = "skypython-dark"
private const val PYTHON_SCOPE = "source.python"

/** One-time registration of the bundled TextMate grammar and theme (assets/textmate/). */
object TextMateSetup {
    @Volatile private var done = false

    @Synchronized
    fun ensure(context: Context) {
        if (done) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        val themes = ThemeRegistry.getInstance()
        val stream = FileProviderRegistry.getInstance().tryGetInputStream(THEME_PATH)
            ?: error("missing asset $THEME_PATH")
        themes.loadTheme(ThemeModel(IThemeSource.fromInputStream(stream, THEME_PATH, null), THEME_NAME).apply { isDark = true })
        themes.setTheme(THEME_NAME)
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        done = true
    }
}

/** TextMate colors with SkyPython's background forced on the text area and gutter. */
private class SkyColorScheme(registry: ThemeRegistry) :
    TextMateColorScheme(registry, registry.currentThemeModel) {
    override fun applyDefault() {
        super.applyDefault()
        setColor(WHOLE_BACKGROUND, EDITOR_BG)
        setColor(LINE_NUMBER_BACKGROUND, EDITOR_BG)
        setColor(LINE_DIVIDER, GUTTER_DIVIDER)
    }

    override fun getColor(type: Int): Int = when (type) {
        WHOLE_BACKGROUND, LINE_NUMBER_BACKGROUND -> EDITOR_BG
        LINE_DIVIDER -> GUTTER_DIVIDER
        else -> super.getColor(type)
    }
}

/** Imperative handle for toolbar / symbol-bar actions on the live editor view. */
class EditorController {
    internal var view: CodeEditor? = null
    internal var flusher: (() -> Unit)? = null

    /**
     * Pushes any not-yet-reported edit to the ViewModel right now. Text changes are reported
     * debounced, so call this before anything that reads the doc text (save, run, close, Files).
     */
    fun flushPendingText() { flusher?.invoke() }

    fun undo() { view?.undo() }
    fun redo() { view?.redo() }

    /** Inserts [text] at the cursor (replacing any selection) and places the cursor after it. */
    fun insert(text: String) {
        view?.insertText(text, text.length)
    }
}

private fun CodeEditor.cursorPosition(): CursorPosition {
    val c = cursor
    return CursorPosition(line = c.leftLine, column = c.leftColumn, lineCount = text.lineCount)
}

/** Applies user settings; each setter is skipped when unchanged so a re-apply doesn't relayout. */
private fun CodeEditor.applyConfig(config: EditorViewConfig) {
    // Sora's setTextSize takes sp and scales by the display's font metrics itself.
    val px = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, config.textSizeSp, resources.displayMetrics,
    )
    if (textSizePx != px) setTextSize(config.textSizeSp)
    if (isWordwrap != config.wordWrap) isWordwrap = config.wordWrap
    if (isLineNumberEnabled != config.lineNumbers) isLineNumberEnabled = config.lineNumbers
    keepScreenOn = config.keepScreenOn
}

/**
 * Sora [CodeEditor] showing the document [docId]. Each doc keeps its own [Content] (and so its
 * own undo history) while this composable lives; [initialText] seeds a doc the first time it is
 * shown. Edits are reported via [onTextChanged], debounced ([TextChangeDebouncer]) so the full text
 * isn't copied on every keystroke; [EditorController.flushPendingText] forces delivery.
 */
@Composable
fun CodeEditor(
    docId: String,
    initialText: String,
    openIds: Set<String>,
    controller: EditorController,
    config: EditorViewConfig,
    onTextChanged: (id: String, text: String) -> Unit,
    onCursorChanged: (CursorPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contents = remember { HashMap<String, Content>() }
    val currentId by rememberUpdatedState(docId)
    val onChange by rememberUpdatedState(onTextChanged)
    val onCursor by rememberUpdatedState(onCursorChanged)
    val scope = rememberCoroutineScope()
    val debouncer = remember { TextChangeDebouncer(scope) { id, text -> onChange(id, text) } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextMateSetup.ensure(ctx)
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                tabWidth = 4
                // Breathing room around the line-number gutter.
                val dp = resources.displayMetrics.density
                lineNumberMarginLeft = 6 * dp
                setDividerMargin(6 * dp, 6 * dp)
                applyConfig(config)
                colorScheme = SkyColorScheme(ThemeRegistry.getInstance())
                setEditorLanguage(TextMateLanguage.create(PYTHON_SCOPE, true))
                subscribeAlways(ContentChangeEvent::class.java) { e ->
                    if (e.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                        // Capture this doc's Content (not the view): after a tab switch the view shows another doc.
                        val content = e.editor.text
                        debouncer.onChanged(currentId) { content.toString() }
                    }
                }
                subscribeAlways(SelectionChangeEvent::class.java) { e -> onCursor(e.editor.cursorPosition()) }
                controller.view = this
                controller.flusher = debouncer::flush
            }
        },
        onRelease = { view ->
            if (controller.view === view) controller.view = null
            view.release()
        },
    )

    // Switch the shown document when the tab changes.
    LaunchedEffect(docId) {
        val view = controller.view ?: return@LaunchedEffect
        debouncer.flush() // the previous doc's pending edit, before the view switches docs
        val content = contents.getOrPut(docId) { Content(initialText) }
        // reuseContentObject = true keeps this doc's Content (and its undo stack) instead of copying it.
        if (view.text !== content) view.setText(content, true, null)
        onCursor(view.cursorPosition())
    }

    // Settings changes apply live to the existing view.
    LaunchedEffect(config) {
        controller.view?.applyConfig(config)
    }

    // Forget contents of closed tabs.
    LaunchedEffect(openIds) {
        contents.keys.retainAll(openIds)
    }

    DisposableEffect(controller) {
        onDispose {
            debouncer.flush()
            contents.clear()
        }
    }
}
