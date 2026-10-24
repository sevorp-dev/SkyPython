package dev.skyloft.skypython.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** One persisted editor tab. [location] is an absolute path (LOCAL) or a URI string (EXTERNAL). */
data class SavedTab(val kind: Kind, val location: String, val name: String) {
    enum class Kind(val code: String) { LOCAL("L"), EXTERNAL("E") }
}

/** Open tabs and the index of the current one (-1 when none). */
data class Session(val tabs: List<SavedTab>, val currentIndex: Int)

/**
 * Line-based text format: a `C\t<index>` line, then one `<kind>\t<location>\t<name>` line per tab.
 * Fields are percent-escaped for `%`, tab and newline. Unknown lines are skipped.
 */
object SessionCodec {
    fun encode(session: Session): String = buildString {
        append("C\t").append(session.currentIndex)
        for (t in session.tabs) {
            append('\n').append(t.kind.code)
                .append('\t').append(esc(t.location))
                .append('\t').append(esc(t.name))
        }
    }

    /** Returns null when nothing was ever stored. */
    fun decode(raw: String?): Session? {
        if (raw == null) return null
        var current = -1
        val tabs = mutableListOf<SavedTab>()
        for (line in raw.split('\n')) {
            val f = line.split('\t')
            if (f.size == 2 && f[0] == "C") {
                current = f[1].toIntOrNull() ?: -1
            } else if (f.size == 3) {
                val kind = SavedTab.Kind.entries.firstOrNull { it.code == f[0] } ?: continue
                tabs += SavedTab(kind, unesc(f[1]), unesc(f[2]))
            }
        }
        return Session(tabs, if (current in tabs.indices) current else -1)
    }

    private fun esc(s: String) = s.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A")
    private fun unesc(s: String) = s.replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")
}

private val Context.sessionDataStore by preferencesDataStore(name = "editor_session")
private val SESSION_KEY = stringPreferencesKey("session")

/** Persists open editor tabs across app restarts (DataStore Preferences). */
class SessionStore(context: Context) {
    private val store = context.applicationContext.sessionDataStore

    /** The stored session, or null if none was ever saved (first run). */
    suspend fun load(): Session? = SessionCodec.decode(store.data.first()[SESSION_KEY])

    suspend fun save(session: Session) {
        val encoded = SessionCodec.encode(session)
        store.edit { it[SESSION_KEY] = encoded }
    }
}
