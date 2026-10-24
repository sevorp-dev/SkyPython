package dev.skyloft.skypython.editor

import dev.skyloft.skypython.data.SavedTab
import java.io.File

/** Result of reopening a saved session: one entry per saved tab (null = couldn't reopen) plus user messages. */
class RestoredTabs(val docs: List<OpenDocument?>, val messages: List<String>)

/**
 * Reopens saved [tabs]. Local tabs are read with [readLocal]; external tabs are opened with
 * [openExternal]. Call off the main thread.
 */
fun restoreTabs(
    tabs: List<SavedTab>,
    readLocal: (File) -> String,
    openExternal: (SavedTab) -> OpenDocument,
): RestoredTabs {
    val messages = mutableListOf<String>()
    val docs = tabs.map { tab ->
        runCatching {
            when (tab.kind) {
                SavedTab.Kind.LOCAL -> File(tab.location).let { OpenDocument.local(it, readLocal(it)) }
                SavedTab.Kind.EXTERNAL -> openExternal(tab)
            }
        }.onFailure { e ->
            // A missing local file was deleted on purpose; an external doc vanishing is news to the user.
            if (tab.kind == SavedTab.Kind.EXTERNAL) {
                val reason = if (e is SecurityException) "permission lost" else e.message ?: "unavailable"
                messages += "Couldn't reopen ${tab.name}: $reason"
            }
        }.getOrNull()
    }
    return RestoredTabs(docs, messages)
}
