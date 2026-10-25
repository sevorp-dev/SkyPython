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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import dev.skyloft.skypython.lang.Diagnostic
import dev.skyloft.skypython.lang.DiagnosticsMapping
import dev.skyloft.skypython.lang.DiagnosticsRunner
import dev.skyloft.skypython.lang.EditorRange
import dev.skyloft.skypython.lang.LanguageService
import dev.skyloft.skypython.lang.Severity
import dev.skyloft.skypython.lang.SignatureHints
import dev.skyloft.skypython.lang.Usage
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.lang.styling.HighlightTextContainer
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // Selection handles: the accent, like the cursor (Sora's default is a stock blue).
        SELECTION_HANDLE -> ACCENT
        PROBLEM_ERROR -> SQUIGGLE_ERROR
        PROBLEM_WARNING -> SQUIGGLE_WARNING
        else -> super.getColor(type)
    }
}

private const val PINCH_SAVE_DELAY_MS = 600L
private val JUMP_RESCROLL_MS = longArrayOf(150L, 500L, 1_200L)
// Completion popup and problem tooltip: the app's raised panel (SlatePanel) and text colours.
private const val POPUP_BG = 0xF5273240.toInt()
private const val POPUP_BORDER = 0xFF334051.toInt()
private const val POPUP_SELECTED = 0xFF4A3B2B.toInt()
private const val POPUP_TEXT = 0xFFE8ECF1.toInt()
private const val POPUP_TEXT_SECONDARY = 0xFF8C99A8.toInt()
private const val POPUP_MATCHED = 0xFFF5A962.toInt()
private const val SQUIGGLE_ERROR = 0xFFFF7B72.toInt()
private const val ACCENT = 0xFFF28C38.toInt()
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

/** Text and a position in it, as the language server takes them: 1-based [line], UTF-16 [column]. */
data class LookupPosition(val source: String, val line: Int, val column: Int)

/** Imperative handle for toolbar / symbol-bar actions on the live editor view. */
/** How a Replace all ended. */
sealed interface ReplaceAllResult {
    data class Replaced(val count: Int) : ReplaceAllResult
    /** The pattern ran too long and was stopped; nothing changed. */
    data object TimedOut : ReplaceAllResult
    /** The text was edited while matching; nothing was replaced. */
    data object TextChanged : ReplaceAllResult
}

/** A Replace all is given up after this long (a runaway pattern). */
const val REPLACE_ALL_TIMEOUT_MS = 10_000L

class EditorController {
    internal var view: CodeEditor? = null
    internal var flusher: (() -> Unit)? = null

    /**
     * Pushes any not-yet-reported edit to the ViewModel right now. Text changes are reported
     * debounced, so call this before anything that reads the doc text (save, run, close, Files).
     */
    fun flushPendingText() { flusher?.invoke() }

    /** Closes the soft keyboard; the console and other screens must not open under it. */
    fun hideKeyboard() {
        val v = view ?: return
        v.hideSoftInput()
        // The selection toolbar is a popup window: it would float over the drawer or a sheet.
        v.getComponent(EditorTextActionWindow::class.java).dismiss()
        v.hideAutoCompleteWindow()
    }

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
            // Against the whole text, so lookbehind, anchors and \b see what Replace all sees.
            val text = v.text
            val start = text.getCharIndex(c.leftLine, c.leftColumn)
            val end = text.getCharIndex(c.rightLine, c.rightColumn)
            v.commitText(q.replacementFor(text, start, end, replacement), false, false)
        }
        v.searcher.gotoNext()
    }

    /**
     * Replaces every match in one undo step. The regex runs off the main thread (a user pattern on
     * a 5 MB file can take long, or forever) and is stopped after [REPLACE_ALL_TIMEOUT_MS]; the
     * edit is applied on the main thread, and only if the text didn't change meanwhile.
     * Cancelling the caller (the find bar closed) stops the search.
     */
    suspend fun replaceAll(replacement: String): ReplaceAllResult {
        val v = view ?: return ReplaceAllResult.Replaced(0)
        val q = query ?: return ReplaceAllResult.Replaced(0)
        if (!v.isEditable) return ReplaceAllResult.Replaced(0)
        val content = v.text
        val before = content.toString()
        val (out, n) = try {
            withTimeout(REPLACE_ALL_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    val job = coroutineContext.job
                    q.replaceAll(before, replacement) { job.ensureActive() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            return ReplaceAllResult.TimedOut
        }
        if (n == 0) return ReplaceAllResult.Replaced(0)
        if (view !== v || v.text !== content || content.toString() != before) return ReplaceAllResult.TextChanged
        val c = v.cursor
        val line = c.leftLine
        val col = c.leftColumn
        val last = content.lineCount - 1
        content.beginBatchEdit()
        content.replace(0, 0, last, content.getColumnCount(last), out)
        content.endBatchEdit()
        select(v, TextSelection(line, col))
        return ReplaceAllResult.Replaced(n)
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

    // ---- code navigation ----

    /** "Go to definition" / "Usages" from the selection toolbar, set by the screen. */
    internal var onGoToDefinition: (() -> Unit)? = null
    internal var onFindUsages: (() -> Unit)? = null

    /** Code hints are on (Settings): the toolbar offers navigation only then. */
    @Volatile internal var navigationEnabled: Boolean = true

    /** The document the view shows now (set by [CodeEditor] when it switches docs). */
    internal var shownDocId: String? = null
    private var pendingJump: Pair<String, EditorRange>? = null

    /** The shown text and the start of the cursor / selection, in server positions (1-based line). */
    fun lookupPosition(): LookupPosition? {
        val v = view ?: return null
        val c = v.cursor
        return LookupPosition(v.text.toString(), c.leftLine + 1, c.leftColumn)
    }

    /** Selects [range] (0-based) of the shown document and scrolls it into view. */
    fun jumpTo(range: EditorRange) {
        val v = view ?: return
        clearUsages()
        // After a tab switch the layout is rebuilt first: select once it has been measured.
        v.post {
            val t = v.text
            val line = range.startLine.coerceIn(0, t.lineCount - 1)
            val len = t.getColumnCount(line)
            val start = range.startColumn.coerceIn(0, len)
            val end = range.endColumn.coerceIn(start, len)
            if (end > start) v.setSelectionRegion(line, start, line, end, true) else v.setSelection(line, start, true)
            v.ensurePositionVisible(line, start)
            v.requestFocus()
            // With word wrap the rows of a just-opened file are laid out in the background: scroll
            // again once they are, or a long file stops short of the target.
            for (delayMs in JUMP_RESCROLL_MS) v.postDelayed({
                val c = v.cursor
                if (v.text === t && c.leftLine == line && c.leftColumn == start) v.ensurePositionVisible(line, start)
            }, delayMs)
        }
    }

    /** [jumpTo] in doc [docId]: now if it is shown, else as soon as the view switches to it. */
    fun jumpWhenShown(docId: String, range: EditorRange) {
        if (shownDocId == docId && view != null) jumpTo(range) else pendingJump = docId to range
    }

    internal fun applyPendingJump(docId: String) {
        val (id, range) = pendingJump ?: return
        if (id != docId) return
        pendingJump = null
        jumpTo(range)
    }

    /** The theme's accent (MaterialTheme primary), set by [CodeEditor]; usages are marked in it. */
    internal var usageAccent: Color = Color.Unspecified

    /** Marks [usages] (server positions) in the shown document until the next edit. */
    fun highlightUsages(usages: List<Usage>) {
        val v = view ?: return
        val t = v.text
        val fill = ConstColor(usageAccent.copy(alpha = 0.22f).toArgb())
        val border = ConstColor(usageAccent.copy(alpha = 0.7f).toArgb())
        val container = HighlightTextContainer()
        for (u in usages) {
            val line = u.line - 1
            if (line !in 0 until t.lineCount) continue
            val len = t.getColumnCount(line)
            val start = u.column.coerceIn(0, len)
            val end = u.endColumn.coerceIn(start, len)
            if (end > start) container.add(HighlightTextContainer.HighlightText(line, start, line, end, fill, border))
        }
        v.highlightTexts = container
        v.invalidate()
    }

    fun clearUsages() {
        val v = view ?: return
        if (v.highlightTexts?.isEmpty() == false) {
            v.highlightTexts = HighlightTextContainer()
            v.invalidate()
        }
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
 * problems are reported through [onDiagnostics] ([docPath] lets it resolve sibling imports),
 * and a parameter hint floats above the cursor inside a call's parentheses.
 *
 * A [readOnly] doc (library code opened by "Go to definition") can't be edited and isn't checked.
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
    readOnly: Boolean = false,
    debugMarks: EditorDebugMarks = EditorDebugMarks.None,
    buffers: DocBuffers<Content> = remember { DocBuffers() },
) {
    val context = LocalContext.current
    // Outlive this composable (owned by the view model): leaving the editor keeps them.
    val contents = buffers
    val currentId by rememberUpdatedState(docId)
    val currentPath by rememberUpdatedState(docPath)
    val currentReadOnly by rememberUpdatedState(readOnly)
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
        if (!completionOn || currentReadOnly) return
        diagnostics.request(currentId, content.documentVersion, content.length, currentPath, immediate) { content.toString() }
    }

    controller.usageAccent = MaterialTheme.colorScheme.primary

    // ---- parameter hint ----
    var hint by remember { mutableStateOf<HintState?>(null) }
    val hintJob = remember { arrayOfNulls<Job>(1) }
    // Esc hides the hint until the text changes again.
    val escapedVersion = remember { longArrayOf(-1L) }
    fun hideHint() {
        hintJob[0]?.cancel()
        hint = null
    }
    fun updateHint(v: CodeEditor) {
        hintJob[0]?.cancel()
        val content = v.text
        val c = v.cursor
        if (!completionOn || currentReadOnly || !v.isEditable || c.isSelected ||
            content.documentVersion == escapedVersion[0] || content.length > DiagnosticsRunner.MAX_CHARS
        ) {
            hint = null
            return
        }
        val line = c.leftLine
        val column = c.leftColumn
        val firstLine = (line - HINT_LOOKBACK_LINES).coerceAtLeast(0)
        val before = content.subContent(firstLine, 0, line, column)
        if (!SignatureHints.mayBeInCall(before)) {
            hint = null
            return
        }
        // Still in the same call: keep the old hint (moved to the cursor) until the new one is in.
        hint = hint?.copy(anchor = hintAnchor(v, line, column))
        val version = content.documentVersion
        hintJob[0] = scope.launch {
            delay(HINT_DELAY_MS)
            val sig = service.signature(content.toString(), line + 1, column, currentPath)
            val cur = v.cursor
            // Stale: another doc, more typing or the cursor moved meanwhile (a newer request follows).
            if (v.text !== content || content.documentVersion != version || cur.isSelected ||
                cur.leftLine != line || cur.leftColumn != column
            ) return@launch
            hint = sig?.let { HintState(SignatureHints.hint(it), hintAnchor(v, line, column)) }
        }
    }

    Box(modifier) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
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
                    SkyTextActionWindow(
                        this,
                        onComment = controller::toggleComment,
                        onIndent = controller::indent,
                        onGoToDefinition = { controller.onGoToDefinition?.invoke() },
                        onFindUsages = { controller.onFindUsages?.invoke() },
                        navigationEnabled = { controller.navigationEnabled },
                    ),
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
                        controller.clearUsages()
                    }
                }
                subscribeAlways(SelectionChangeEvent::class.java) { e ->
                    onCursor(e.editor.cursorPosition())
                    controller.publishFind(e.editor)
                    updateHint(e.editor)
                }
                subscribeAlways(ScrollEvent::class.java) { e ->
                    val h = hint ?: return@subscribeAlways
                    val c = e.editor.cursor
                    hint = h.copy(anchor = hintAnchor(e.editor, c.leftLine, c.leftColumn))
                }
                subscribeAlways(PublishSearchResultEvent::class.java) { e -> controller.onSearchResult(e.editor) }
                subscribeAlways(EditorKeyEvent::class.java) { e ->
                    if (e.eventType != EditorKeyEvent.Type.DOWN) return@subscribeAlways
                    if (e.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && hint != null) {
                        escapedVersion[0] = e.editor.text.documentVersion
                        hideHint()
                        e.markAsConsumed()
                        return@subscribeAlways
                    }
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
            if (view.text === contents[currentId]) contents.saveScroll(currentId, view.offsetX, view.offsetY)
            if (controller.view === view) controller.view = null
            diagnostics.cancel()
            hideHint()
            view.release()
        },
    )
    hint?.let { SignatureHintBubble(it) }
    }

    // Switch the shown document when the tab changes.
    LaunchedEffect(docId, readOnly) {
        val view = controller.view ?: return@LaunchedEffect
        debouncer.flush() // the previous doc's pending edit, before the view switches docs
        hideHint()
        controller.clearUsages()
        controller.shownDocId?.let { shown ->
            if (shown != docId && view.text === contents[shown]) contents.saveScroll(shown, view.offsetX, view.offsetY)
        }
        val content = contents.getOrPut(docId) { Content(initialText) }
        // reuseContentObject = true keeps this doc's Content (its cursor and undo stack) instead of copying it.
        if (view.text !== content) {
            view.setText(content, true, null)
            contents.scrollOf(docId)?.let { view.restoreScroll(it) }
        }
        view.isEditable = !readOnly
        if (readOnly) view.hideSoftInput()
        controller.shownDocId = docId
        controller.applyPendingJump(docId)
        onCursor(view.cursorPosition())
        // The doc's last known problems at once, then a fresh check.
        val check = config.codeCompletion && !readOnly
        val known = problems[docId].orEmpty()
        view.diagnostics = if (check) diagnosticsContainer(content, known) else null
        onProblems(if (check) known else emptyList())
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
        controller.navigationEnabled = config.codeCompletion
        if (!config.codeCompletion) {
            hideHint()
            controller.clearUsages()
        }
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
        contents.retain(openIds)
        problems.keys.retainAll(openIds)
    }

    DisposableEffect(controller) {
        onDispose {
            debouncer.flush()
            diagnostics.cancel()
            hintJob[0]?.cancel()
            controller.shownDocId = null
            // The contents stay (cursor, undo history, scroll): see [DocBuffers].
            problems.clear()
        }
    }
}

/**
 * Scrolls to [s] (clamped) once the view is laid out, and again while word wrap is still laying
 * the rows out in the background, unless the user scrolled meanwhile.
 */
private fun CodeEditor.restoreScroll(s: DocBuffers.Scroll) {
    val last = intArrayOf(-1, -1)
    val apply = {
        val x = s.x.coerceIn(0, scrollMaxX.coerceAtLeast(0))
        val y = s.y.coerceIn(0, scrollMaxY.coerceAtLeast(0))
        scroller.forceFinished(true)
        scroller.startScroll(x, y, 0, 0, 0)
        scroller.abortAnimation()
        last[0] = x
        last[1] = y
        invalidate()
    }
    post { apply() }
    for (delayMs in JUMP_RESCROLL_MS) postDelayed({
        if (offsetX == last[0] && offsetY == last[1] && (last[0] != s.x || last[1] != s.y)) apply()
    }, delayMs)
}
