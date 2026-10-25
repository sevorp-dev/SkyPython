package dev.skyloft.skypython.debug

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A breakpoint on [line] (1-based), stopping only when [condition] (Python) is true, if set. */
data class Breakpoint(val line: Int, val condition: String? = null)

/**
 * One text change, in editor positions (0-based lines and columns), as Sora reports it:
 * [Insert] from (startLine, startColumn) to endLine in the new text, [Delete] of the range from
 * (startLine, startColumn) to endLine of the old text.
 */
sealed interface LineEdit {
    val startLine: Int
    val startColumn: Int
    val endLine: Int

    data class Insert(override val startLine: Int, override val startColumn: Int, override val endLine: Int) : LineEdit
    data class Delete(override val startLine: Int, override val startColumn: Int, override val endLine: Int) : LineEdit
}

/** Keeps breakpoints on the code they were set on while lines are added and removed above it. */
object BreakpointShift {

    /** [breakpoints] after [edit]: sorted by line, at most one per line (the first one kept). */
    fun apply(breakpoints: List<Breakpoint>, edit: LineEdit): List<Breakpoint> {
        val delta = edit.endLine - edit.startLine
        if (delta == 0 || breakpoints.isEmpty()) return breakpoints
        val l1 = edit.startLine
        val l2 = edit.endLine
        val moved = breakpoints.mapNotNull { bp ->
            val line = bp.line - 1
            val newLine = when (edit) {
                // The text after the insertion point goes to endLine: a whole line if inserted at column 0.
                is LineEdit.Insert -> if (line > l1 || (line == l1 && edit.startColumn == 0)) line + delta else line
                is LineEdit.Delete -> when {
                    line < l1 -> line
                    // Deleted from its very start: what is left of the line is gone.
                    line == l1 -> if (edit.startColumn == 0) null else line
                    line < l2 -> null
                    // The rest of the last line is joined onto the first one.
                    line == l2 -> l1
                    else -> line - delta
                }
            }
            newLine?.let { bp.copy(line = it + 1) to (it == line) }
        }
        // On a clash the breakpoint that was already on the line wins.
        return moved.sortedWith(compareBy({ it.first.line }, { if (it.second) 0 else 1 }))
            .map { it.first }
            .distinctBy { it.line }
    }
}

/**
 * Breakpoints of every file (keyed by canonical path, the spelling the debugger reports back)
 * and the watch expressions. Changes are published on [state] / [watches] and handed to
 * [persist] as JSON; [saved] is that JSON from a previous run.
 */
class BreakpointStore(saved: String?, private val persist: (String) -> Unit) {

    private val _state = MutableStateFlow<Map<String, List<Breakpoint>>>(emptyMap())
    val state: StateFlow<Map<String, List<Breakpoint>>> = _state.asStateFlow()

    private val _watches = MutableStateFlow<List<String>>(emptyList())
    val watches: StateFlow<List<String>> = _watches.asStateFlow()

    init {
        if (saved != null) load(saved)
    }

    /** Adds or removes the breakpoint on [line] (1-based) of [path]; true if there is one now. */
    @Synchronized
    fun toggle(path: String, line: Int): Boolean {
        val list = _state.value[path].orEmpty()
        val now = if (list.any { it.line == line }) list.filter { it.line != line } else (list + Breakpoint(line)).sortedBy { it.line }
        update(path, now)
        return now.any { it.line == line }
    }

    /** Sets (or with a blank [condition], clears) the condition of the breakpoint on [line]. */
    @Synchronized
    fun setCondition(path: String, line: Int, condition: String?) {
        val cond = condition?.trim()?.takeIf { it.isNotEmpty() }
        val list = _state.value[path].orEmpty()
        val now = if (list.any { it.line == line }) list.map { if (it.line == line) it.copy(condition = cond) else it }
        else (list + Breakpoint(line, cond)).sortedBy { it.line }
        update(path, now)
    }

    @Synchronized
    fun applyEdit(path: String, edit: LineEdit) {
        val list = _state.value[path] ?: return
        val now = BreakpointShift.apply(list, edit)
        if (now != list) update(path, now)
    }

    @Synchronized
    fun rename(from: String, to: String) {
        val list = _state.value[from] ?: return
        _state.value = _state.value - from + (to to list)
        save()
    }

    @Synchronized
    fun addWatch(expression: String) {
        val e = expression.trim()
        if (e.isEmpty()) return
        _watches.value = _watches.value + e
        save()
    }

    @Synchronized
    fun removeWatch(index: Int) {
        val list = _watches.value
        if (index !in list.indices) return
        _watches.value = list.filterIndexed { i, _ -> i != index }
        save()
    }

    private fun update(path: String, list: List<Breakpoint>) {
        _state.value = if (list.isEmpty()) _state.value - path else _state.value + (path to list)
        save()
    }

    private fun save() {
        val root = JsonObject()
        val files = JsonObject()
        for ((path, list) in _state.value) {
            files.add(path, JsonArray().apply {
                list.forEach { bp ->
                    add(JsonObject().apply {
                        addProperty("line", bp.line)
                        bp.condition?.let { addProperty("condition", it) }
                    })
                }
            })
        }
        root.add("breakpoints", files)
        root.add("watches", JsonArray().apply { _watches.value.forEach { add(it) } })
        persist(root.toString())
    }

    private fun load(json: String) {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val files = root.getAsJsonObject("breakpoints")
            _state.value = files?.entrySet()?.associate { (path, list) ->
                path to list.asJsonArray.map {
                    val o = it.asJsonObject
                    Breakpoint(o["line"].asInt, o["condition"]?.takeIf { c -> !c.isJsonNull }?.asString)
                }.sortedBy { it.line }.distinctBy { it.line }
            }.orEmpty().filterValues { it.isNotEmpty() }
            _watches.value = root.getAsJsonArray("watches")?.map { it.asString }.orEmpty()
        } catch (e: RuntimeException) {
            // Unreadable (hand-edited, truncated): start empty rather than crash.
            _state.value = emptyMap()
            _watches.value = emptyList()
        }
    }
}
