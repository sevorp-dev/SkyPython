package dev.skyloft.skypython.editor

import io.github.rosemoe.sora.widget.SelectionMovement
import dev.skyloft.skypython.settings.CodeFontChoice
import dev.skyloft.skypython.R
import androidx.core.content.res.ResourcesCompat
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.TextSizeChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
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
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import dev.skyloft.skypython.settings.MAX_FONT_SIZE_SP
import dev.skyloft.skypython.settings.MIN_FONT_SIZE_SP
import dev.skyloft.skypython.settings.symbolInsertion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource

private const val EDITOR_BG = 0xFF1C2530.toInt()
private const val GUTTER_DIVIDER = 0xFF2A3644.toInt()
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
        COMPLETION_WND_BACKGROUND, DIAGNOSTIC_TOOLTIP_BACKGROUND -> POPUP_BG
        COMPLETION_WND_CORNER -> POPUP_BORDER
        // Find matches: the selection colour, so highlighted (often yellow string) text stays readable.
        COMPLETION_WND_ITEM_CURRENT, MATCHED_TEXT_BACKGROUND -> POPUP_SELECTED
        COMPLETION_WND_TEXT_PRIMARY, DIAGNOSTIC_TOOLTIP_BRIEF_MSG -> POPUP_TEXT
        COMPLETION_WND_TEXT_SECONDARY, DIAGNOSTIC_TOOLTIP_DETAILED_MSG -> POPUP_TEXT_SECONDARY
        COMPLETION_WND_TEXT_MATCHED -> POPUP_MATCHED
        // Selection toolbar (cut / copy / paste …): the same dark popup as the suggestions.
        TEXT_ACTION_WINDOW_BACKGROUND -> POPUP_BG
        TEXT_ACTION_WINDOW_ICON_COLOR -> POPUP_TEXT
        PROBLEM_ERROR -> SQUIGGLE_ERROR
        PROBLEM_WARNING -> SQUIGGLE_WARNING
        else -> super.getColor(type)
    }
}

private const val PINCH_SAVE_DELAY_MS = 600L
// Completion popup and problem tooltip: the app's raised panel (SlatePanel) and text colours.
private const val POPUP_BG = 0xF5273240.toInt()
private const val POPUP_BORDER = 0xFF334051.toInt()
private const val POPUP_SELECTED = 0xFF4A3B2B.toInt()
private const val POPUP_TEXT = 0xFFE8ECF1.toInt()
private const val POPUP_TEXT_SECONDARY = 0xFF8C99A8.toInt()
private const val POPUP_MATCHED = 0xFFF5A962.toInt()
private const val SQUIGGLE_ERROR = 0xFFFF7B72.toInt()
private const val SQUIGGLE_WARNING = 0xFFE3B95C.toInt()

/**
 * Completion-list badge: the kind's first letter on a soft tonal circle in the accent orange,
 * instead of Sora's saturated per-kind colours.
 */
internal class CompletionBadge(kind: CompletionItemKind) : Drawable() {
    private val letter = kind.name.first().uppercase()
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_BG }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = POPUP_MATCHED
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle)
        text.textSize = r
        canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() - (text.descent() + text.ascent()) / 2, text)
    }

    override fun setAlpha(alpha: Int) {
        circle.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        circle.colorFilter = colorFilter
        text.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private const val BADGE_BG = 0xFF3D3328.toInt()

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

    /** Focuses the code and opens the soft keyboard (the symbol bar's keyboard key). */
    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        v.showSoftInput()
    }

    /** Moves the cursor one character left / right (the symbol bar's arrow keys). */
    fun cursorLeft() { view?.moveSelection(SelectionMovement.LEFT) }
    fun cursorRight() { view?.moveSelection(SelectionMovement.RIGHT) }

    fun undo() { view?.undo() }
    fun redo() { view?.redo() }

    /** Auto-close brackets/quotes (the setting); also used by [insertSymbol]. */
    internal var autoClose: Boolean = true

    /** Inserts [text] at the cursor (replacing any selection) and places the cursor after it. */
    fun insert(text: String) {
        view?.insertText(text, text.length)
    }

    /** A symbol-bar key: like [insert], but `(` `[` `{` and quotes get their pair when auto-close is on. */
    fun insertSymbol(text: String) {
        val ins = symbolInsertion(text, autoClose)
        view?.insertText(ins.text, ins.cursor)
    }

    // ---- line commands ----

    fun toggleComment() = applyLineEdit(LineEdits::toggleComment)
    fun indent() = applyLineEdit(LineEdits::indent)
    fun outdent() = applyLineEdit(LineEdits::outdent)
    fun duplicateLine() = applyLineEdit(LineEdits::duplicate)
    fun deleteLine() = applyLineEdit(LineEdits::deleteLines)
    fun moveLineUp() = applyLineEdit(LineEdits::moveUp)
    fun moveLineDown() = applyLineEdit(LineEdits::moveDown)

    private fun applyLineEdit(command: (List<String>, TextSelection) -> LineEdit?) {
        val v = view ?: return
        if (!v.isEditable) return
        val content = v.text
        val lines = object : AbstractList<String>() {
            override val size get() = content.lineCount
            override fun get(index: Int) = content.getLineString(index)
        }
        val c = v.cursor
        val edit = command(lines, TextSelection(c.leftLine, c.leftColumn, c.rightLine, c.rightColumn)) ?: return
        content.beginBatchEdit()
        content.replace(edit.startLine, edit.startColumn, edit.endLine, edit.endColumn, edit.text)
        content.endBatchEdit()
        select(v, edit.selection)
    }

    private fun select(v: CodeEditor, s: TextSelection) {
        val t = v.text
        fun line(l: Int) = l.coerceIn(0, t.lineCount - 1)
        val sl = line(s.startLine)
        val el = line(s.endLine)
        val sc = s.startColumn.coerceIn(0, t.getColumnCount(sl))
        val ec = s.endColumn.coerceIn(0, t.getColumnCount(el))
        if (sl == el && sc == ec) v.setSelection(sl, sc) else v.setSelectionRegion(sl, sc, el, ec)
    }

    /** Lines in the shown document (at least 1). */
    fun lineCount(): Int = view?.text?.lineCount ?: 1

    /** Moves the cursor to the start of 0-based [line] and scrolls there. */
    fun goToLine(line: Int) {
        val v = view ?: return
        v.setSelection(line.coerceIn(0, v.text.lineCount - 1), 0, true)
        v.requestFocus()
    }

    // ---- find & replace ----

    /** Find results for the bar: [index] of the selected match (-1 = none selected) of [count]. */
    internal var onFindState: ((index: Int, count: Int) -> Unit)? = null
    private var query: FindQuery? = null
    private var jumpOnResult = false

    /** Highlights every match of [q] and selects the first one at or after the cursor. */
    fun find(q: FindQuery) {
        val v = view ?: return
        if (!q.isSearchable()) {
            stopFind()
            return
        }
        if (q == query && v.searcher.hasQuery()) return
        query = q
        jumpOnResult = true
        // Every mode is a regex here, so the highlighter and replace-all agree (see FindQuery).
        v.searcher.search(q.pattern!!, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION, !q.matchCase))
    }

    fun stopFind() {
        query = null
        jumpOnResult = false
        val v = view
        if (v != null && v.searcher.hasQuery()) v.searcher.stopSearch()
        onFindState?.invoke(-1, 0)
    }

    fun findNext() {
        val v = view ?: return
        if (v.searcher.hasQuery()) { v.searcher.gotoNext(); publishFind(v) }
    }

    fun findPrevious() {
        val v = view ?: return
        if (v.searcher.hasQuery()) { v.searcher.gotoPrevious(); publishFind(v) }
    }

    /** Replaces the selected match (or jumps to the next one if none is selected), then moves on. */
    fun replaceCurrent(replacement: String) {
        val v = view ?: return
        val q = query ?: return
        if (!v.searcher.hasQuery() || !v.isEditable) return
        if (v.searcher.isMatchedPositionSelected) {
            val c = v.cursor
            val matched = v.text.subContent(c.leftLine, c.leftColumn, c.rightLine, c.rightColumn).toString()
            v.commitText(q.replacementFor(matched, replacement), false, false)
        }
        v.searcher.gotoNext()
    }

    /** Replaces every match in one undo step; returns how many were replaced. */
    fun replaceAll(replacement: String): Int {
        val v = view ?: return 0
        val q = query ?: return 0
        if (!v.isEditable) return 0
        val content = v.text
        val (out, n) = q.replaceAll(content.toString(), replacement)
        if (n == 0) return 0
        val c = v.cursor
        val line = c.leftLine
        val col = c.leftColumn
        val last = content.lineCount - 1
        content.beginBatchEdit()
        content.replace(0, 0, last, content.getColumnCount(last), out)
        content.endBatchEdit()
        select(v, TextSelection(line, col))
        return n
    }

    internal fun onSearchResult(v: CodeEditor) {
        if (jumpOnResult && v.searcher.hasQuery()) {
            jumpOnResult = false
            // From the start of the current selection, so a match under the cursor counts.
            val c = v.cursor
            if (c.isSelected) v.setSelection(c.leftLine, c.leftColumn, false)
            if (v.searcher.matchedPositionCount > 0) v.searcher.gotoNext()
        }
        publishFind(v)
    }

    internal fun publishFind(v: CodeEditor) {
        val cb = onFindState ?: return
        if (!v.searcher.hasQuery()) cb(-1, 0)
        else cb(v.searcher.currentMatchedPositionIndex, v.searcher.matchedPositionCount)
    }

    // ---- keyboard shortcuts / zoom ----

    /** Hardware-keyboard shortcut pressed in the editor ([editorShortcut]). */
    internal var onShortcut: ((EditorShortcut) -> Unit)? = null

    /** The user pinch-zoomed to this font size (whole sp, already clamped). */
    internal var onPinchFontSize: ((Int) -> Unit)? = null

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

@Volatile private var jetBrainsMono: Typeface? = null

/**
 * The code typeface for [choice]: bundled JetBrains Mono (loaded once), or the system monospace.
 * Shared by the editor, the console and the terminal.
 */
fun codeTypeface(context: Context, choice: CodeFontChoice): Typeface = when (choice) {
    CodeFontChoice.SystemMono -> Typeface.MONOSPACE
    CodeFontChoice.JetBrainsMono -> jetBrainsMono
        ?: (runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) }.getOrNull() ?: Typeface.MONOSPACE)
            .also { jetBrainsMono = it }
}

/** True while [applyConfig] sets the text size, so that change isn't taken for a pinch. */
private var applyingTextSize = false

/** Applies user settings; each setter is skipped when unchanged so a re-apply doesn't relayout. */
internal fun CodeEditor.applyConfig(config: EditorViewConfig) {
    val face = codeTypeface(context, config.codeFont)
    if (typefaceText !== face) {
        typefaceText = face
        typefaceLineNumber = face
    }
    // Sora's setTextSize takes sp and scales by the display's font metrics itself.
    val px = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, config.textSizeSp, resources.displayMetrics,
    )
    if (textSizePx != px) {
        applyingTextSize = true
        try { setTextSize(config.textSizeSp) } finally { applyingTextSize = false }
    }
    props.symbolPairAutoCompletion = config.autoClosePairs
    // setInputType restarts the IME connection, so only on a real change.
    val type = editorInputType(config.keyboardSuggestions)
    if (inputType != type) inputType = type
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
 *
 * [debugMarks]: the debugger's breakpoints and paused line for this doc ([DebugAwareCodeEditor]).
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
    debugMarks: EditorDebugMarks = EditorDebugMarks.None,
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
            DebugAwareCodeEditor(ctx).apply {
                tabWidth = 4
                // Backspace in leading spaces removes a whole indent level (4 spaces).
                props.deleteMultiSpaces = -1
                // Pinch-to-zoom within the Settings font range.
                val sp = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
                setScaleTextSizes(MIN_FONT_SIZE_SP * sp, MAX_FONT_SIZE_SP * sp)
                replaceComponent(
                    EditorTextActionWindow::class.java,
                    SkyTextActionWindow(this, onComment = controller::toggleComment, onIndent = controller::indent),
                )
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
                subscribeAlways(SelectionChangeEvent::class.java) { e ->
                    onCursor(e.editor.cursorPosition())
                    controller.publishFind(e.editor)
                }
                subscribeAlways(PublishSearchResultEvent::class.java) { e -> controller.onSearchResult(e.editor) }
                subscribeAlways(EditorKeyEvent::class.java) { e ->
                    if (e.eventType != EditorKeyEvent.Type.DOWN) return@subscribeAlways
                    val shortcut = editorShortcut(e.keyCode, e.isCtrlPressed, e.isShiftPressed) ?: return@subscribeAlways
                    val handler = controller.onShortcut ?: return@subscribeAlways
                    if (e.repeatCount == 0) handler(shortcut)
                    e.markAsConsumed()
                }
                // Pinch-zoom: keep the size the user settles on (debounced, rounded to whole sp).
                val savePinch = Runnable { controller.onPinchFontSize?.invoke(pinchFontSp(textSizePx, sp)) }
                subscribeAlways(TextSizeChangeEvent::class.java) { e ->
                    if (applyingTextSize) return@subscribeAlways
                    e.editor.removeCallbacks(savePinch)
                    e.editor.postDelayed(savePinch, PINCH_SAVE_DELAY_MS)
                }
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

    // Debugger marks; the paused line is scrolled into view (after the doc switch above).
    SideEffect { (controller.view as? DebugAwareCodeEditor)?.marks = debugMarks }
    LaunchedEffect(docId, debugMarks.executionLine) {
        val line = debugMarks.executionLine ?: return@LaunchedEffect
        val view = controller.view ?: return@LaunchedEffect
        if (line - 1 in 0 until view.text.lineCount) view.ensurePositionVisible(line - 1, 0)
    }

    // Settings changes apply live to the existing view.
    LaunchedEffect(config) {
        controller.autoClose = config.autoClosePairs
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
