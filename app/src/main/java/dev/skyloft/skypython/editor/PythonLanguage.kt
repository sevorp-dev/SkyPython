package dev.skyloft.skypython.editor

import android.os.Bundle
import dev.skyloft.skypython.lang.Completion
import dev.skyloft.skypython.lang.CompletionMapping
import dev.skyloft.skypython.lang.DiagnosticsRunner
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.QuickQuoteHandler
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

/**
 * Python for Sora: TextMate highlighting/indenting from [base], completions from the language
 * server through [completer]. Sora calls [requireAutoComplete] on its own worker thread and
 * interrupts it when the user keeps typing (INTERRUPTION_LEVEL_STRONG), which cancels the
 * server request too. When the server has nothing (still starting, failed, huge file) the
 * popup falls back to TextMate's plain word completion.
 */
class PythonLanguage(
    private val base: TextMateLanguage,
    private val completer: Completer,
) : Language by base {

    /** Blocking completion call, made on Sora's completion thread; null = no server answer. */
    fun interface Completer {
        @Throws(InterruptedException::class)
        fun complete(source: String, line: Int, column: Int): List<Completion>?
    }

    /** The "Code completion" setting: off means no popup at all. */
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value
            base.isAutoCompleteEnabled = value
        }

    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_STRONG

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        if (!enabled) return
        val line = content.getLine(position.line)
        if (!CompletionMapping.shouldComplete(line, position.column)) return
        val items = try {
            // Fast typing: the next keystroke interrupts this pause, so no request is sent.
            Thread.sleep(TYPING_PAUSE_MS)
            val text = content.reference
            if (text.length > DiagnosticsRunner.MAX_CHARS) null
            else completer.complete(text.toString(), position.line + 1, position.column)
        } catch (e: InterruptedException) {
            throw CompletionCancelledException()
        }
        if (items == null) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        publisher.addItems(items.map { c ->
            val p = CompletionMapping.proposal(c)
            SimpleCompletionItem(p.label, p.type, p.prefixLength, p.commitText).kind(p.kind)
        })
    }

    // Java default methods: delegated explicitly, `by base` doesn't cover them.
    override fun getIndentAdvance(
        content: ContentReference,
        line: Int,
        column: Int,
        spaceCountOnLine: Int,
        tabCountOnLine: Int,
    ): Int = base.getIndentAdvance(content, line, column, spaceCountOnLine, tabCountOnLine)

    override fun getQuickQuoteHandler(): QuickQuoteHandler? = base.quickQuoteHandler

    private companion object {
        const val TYPING_PAUSE_MS = 60L
    }
}
