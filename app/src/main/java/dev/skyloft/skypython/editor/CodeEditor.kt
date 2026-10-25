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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.skyloft.skypython.lang.Diagnostic
import dev.skyloft.skypython.lang.DiagnosticsMapping
import dev.skyloft.skypython.lang.DiagnosticsRunner
import dev.skyloft.skypython.lang.LanguageService
import dev.skyloft.skypython.lang.Severity
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import kotlinx.coroutines.runBlocking
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

private const val EDITOR_BG = 0xFF0B1230.toInt()
private const val GUTTER_DIVIDER = 0xFF1E2A55.toInt()
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
        // The theme has no suggest-widget / tooltip colors; Sora's defaults are light-on-light.
        COMPLETION_WND_BACKGROUND, COMPLETION_WND_CORNER, DIAGNOSTIC_TOOLTIP_BACKGROUND -> POPUP_BG
        COMPLETION_WND_ITEM_CURRENT -> POPUP_SELECTED
        COMPLETION_WND_TEXT_PRIMARY, DIAGNOSTIC_TOOLTIP_BRIEF_MSG -> POPUP_TEXT
        COMPLETION_WND_TEXT_SECONDARY, DIAGNOSTIC_TOOLTIP_DETAILED_MSG -> POPUP_TEXT_SECONDARY
        COMPLETION_WND_TEXT_MATCHED -> POPUP_MATCHED
        PROBLEM_ERROR -> SQUIGGLE_ERROR
        PROBLEM_WARNING -> SQUIGGLE_WARNING
        else -> super.getColor(type)
    }
}

private const val POPUP_BG = 0xFF16212C.toInt()
private const val POPUP_SELECTED = 0xFF264F78.toInt()
private const val POPUP_TEXT = 0xFFD4D4D4.toInt()
private const val POPUP_TEXT_SECONDARY = 0xFF93A4B3.toInt()
private const val POPUP_MATCHED = 0xFF4DAAFC.toInt()
private const val SQUIGGLE_ERROR = 0xFFF14C4C.toInt()
private const val SQUIGGLE_WARNING = 0xFFCCA700.toInt()

/** Imperative handle for toolbar / symbol-bar actions on the live editor view. */
class EditorController {
    internal var view: CodeEditor? = null
    internal var flusher: (() -> Unit)? = null

    /**
     * Pushes any not-yet-reported edit to the ViewModel right now. Text changes are reported
     * debounced, so call this before anything that reads the doc text (save, run, close, Files).
     */
    fun flushPendingText() { flusher?.invoke() }

    /** Closes the soft keyboard; the console and other screens must not open under it. */
    fun hideKeyboard() { view?.hideSoftInput() }

    fun undo() { view?.undo() }
    fun redo() { view?.redo() }

    /** Inserts [text] at the cursor (replacing any selection) and places the cursor after it. */
    fun insert(text: String) {
        view?.insertText(text, text.length)
    }

    /**
     * Moves the cursor to [d] (server positions: 1-based line) and scrolls there; as if tapped,
     * so the problem's tooltip opens.
     */
    fun goTo(d: Diagnostic) {
        val v = view ?: return
        val content = v.text
        val r = DiagnosticsMapping.toEditorRange(d, content.lineCount) { content.getColumnCount(it) }
        v.setSelection(r.startLine, r.startColumn, true, SelectionChangeEvent.CAUSE_TAP)
    }
}

/** Squiggles for [diagnostics] in [content]; tapping one shows its message (Sora's tooltip). */
private fun diagnosticsContainer(content: Content, diagnostics: List<Diagnostic>): DiagnosticsContainer {
    val container = DiagnosticsContainer()
    diagnostics.forEachIndexed { i, d ->
        val r = DiagnosticsMapping.toEditorRange(d, content.lineCount) { content.getColumnCount(it) }
        val start = content.getCharIndex(r.startLine, r.startColumn)
        val end = content.getCharIndex(r.endLine, r.endColumn)
        if (end > start) {
            val severity = if (d.severity == Severity.ERROR) DiagnosticRegion.SEVERITY_ERROR else DiagnosticRegion.SEVERITY_WARNING
            container.addDiagnostic(DiagnosticRegion(start, end, severity, i.toLong(), DiagnosticDetail(d.message)))
        }
    }
    return container
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
    // Wrap at the exact width, not at word boundaries: Sora 0.24's anti-word-breaking splits a
    // line again at every following '.' or space, so `print("a", statistics.mode(x))` took three
    // rows with a lone `mode(x))` and the closing `")` of a string dropped to a row of its own.
    if (isWordwrap != config.wordWrap || isAntiWordBreaking) setWordwrap(config.wordWrap, false)
    if (isLineNumberEnabled != config.lineNumbers) isLineNumberEnabled = config.lineNumbers
    keepScreenOn = config.keepScreenOn
}

/**
 * Sora [CodeEditor] showing the document [docId]. Each doc keeps its own [Content] (and so its
 * own undo history) while this composable lives; [initialText] seeds a doc the first time it is
 * shown. Edits are reported via [onTextChanged], debounced ([TextChangeDebouncer]) so the full text
 * isn't copied on every keystroke; [EditorController.flushPendingText] forces delivery.
 *
 * With [EditorViewConfig.codeCompletion] on, the language server ([LanguageService], started
 * when this is first shown) supplies the completion popup and error squiggles; the shown doc's
 * problems are reported through [onDiagnostics] ([docPath] lets it resolve sibling imports).
 */
@Composable
fun CodeEditor(
    docId: String,
    docPath: String?,
    initialText: String,
    openIds: Set<String>,
    controller: EditorController,
    config: EditorViewConfig,
    onTextChanged: (id: String, text: String) -> Unit,
    onCursorChanged: (CursorPosition) -> Unit,
    onDiagnostics: (List<Diagnostic>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contents = remember { HashMap<String, Content>() }
    val currentId by rememberUpdatedState(docId)
    val currentPath by rememberUpdatedState(docPath)
    val completionOn by rememberUpdatedState(config.codeCompletion)
    val onChange by rememberUpdatedState(onTextChanged)
    val onCursor by rememberUpdatedState(onCursorChanged)
    val onProblems by rememberUpdatedState(onDiagnostics)
    val scope = rememberCoroutineScope()
    val debouncer = remember { TextChangeDebouncer(scope) { id, text -> onChange(id, text) } }
    val service = remember { LanguageService.get(context) }
    // Last diagnostics per doc, so switching tabs shows them again at once.
    val problems = remember { HashMap<String, List<Diagnostic>>() }
    val diagnostics = remember {
        DiagnosticsRunner(
            scope = scope,
            diagnose = { source, path -> service.diagnose(source, path) },
            onResult = { r ->
                val view = controller.view
                val content = contents[r.docId]
                // Stale (the text changed since, or another doc is shown): a newer request follows.
                if (view != null && content != null && view.text === content && r.docId == currentId &&
                    content.documentVersion == r.version && completionOn
                ) {
                    problems[r.docId] = r.diagnostics
                    view.diagnostics = diagnosticsContainer(content, r.diagnostics)
                    onProblems(r.diagnostics)
                }
            },
        )
    }
    fun requestDiagnostics(content: Content, immediate: Boolean) {
        if (!completionOn) return
        diagnostics.request(currentId, content.documentVersion, content.length, currentPath, immediate) { content.toString() }
    }

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
                val language = PythonLanguage(TextMateLanguage.create(PYTHON_SCOPE, true)) { source, line, column ->
                    // Sora's completion thread; interrupting it (more typing) cancels the request.
                    runBlocking { service.complete(source, line, column, currentPath) }
                }
                language.enabled = config.codeCompletion
                setEditorLanguage(language)
                subscribeAlways(ContentChangeEvent::class.java) { e ->
                    if (e.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                        // Capture this doc's Content (not the view): after a tab switch the view shows another doc.
                        val content = e.editor.text
                        debouncer.onChanged(currentId) { content.toString() }
                        requestDiagnostics(content, immediate = false)
                    }
                }
                subscribeAlways(SelectionChangeEvent::class.java) { e -> onCursor(e.editor.cursorPosition()) }
                controller.view = this
                controller.flusher = debouncer::flush
            }
        },
        onRelease = { view ->
            if (controller.view === view) controller.view = null
            diagnostics.cancel()
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
        // The doc's last known problems at once, then a fresh check.
        val known = problems[docId].orEmpty()
        view.diagnostics = if (config.codeCompletion) diagnosticsContainer(content, known) else null
        onProblems(if (config.codeCompletion) known else emptyList())
        requestDiagnostics(content, immediate = true)
    }

    // Settings changes apply live to the existing view.
    LaunchedEffect(config) {
        controller.view?.applyConfig(config)
    }

    // Code completion on: start the server now (in the background), so it is warm by the time
    // the first suggestion is wanted. Off: stop it and clear every mark.
    LaunchedEffect(config.codeCompletion) {
        val view = controller.view
        (view?.editorLanguage as? PythonLanguage)?.enabled = config.codeCompletion
        if (config.codeCompletion) {
            service.warmUp()
            contents[currentId]?.let { if (view?.text === it) requestDiagnostics(it, immediate = true) }
        } else {
            diagnostics.cancel()
            problems.clear()
            view?.diagnostics = null
            onProblems(emptyList())
            service.shutdown()
        }
    }

    // Forget contents of closed tabs.
    LaunchedEffect(openIds) {
        contents.keys.retainAll(openIds)
        problems.keys.retainAll(openIds)
    }

    DisposableEffect(controller) {
        onDispose {
            debouncer.flush()
            diagnostics.cancel()
            contents.clear()
            problems.clear()
        }
    }
}
