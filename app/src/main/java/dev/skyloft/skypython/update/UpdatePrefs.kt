package dev.skyloft.skypython.update

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the updater remembers between starts. */
data class UpdatePrefsData(
    /** "Check for updates automatically" (Settings). */
    val autoCheck: Boolean = true,
    /** When a check last reached GitHub (ms); null = never. */
    val lastCheckAt: Long? = null,
    /** "Skip this version". */
    val skipped: String? = null,
    /** The version whose banner was closed with ✕. */
    val dismissed: String? = null,
    /** The newest release the last check found, and its notes (shown without asking again). */
    val latest: String? = null,
    val latestNotes: String? = null,
)

interface UpdatePrefs {
    val data: Flow<UpdatePrefsData>
    suspend fun edit(transform: (UpdatePrefsData) -> UpdatePrefsData)
}

/** In memory (tests). */
class MemoryUpdatePrefs(initial: UpdatePrefsData = UpdatePrefsData()) : UpdatePrefs {
    private val flow = MutableStateFlow(initial)
    var value: UpdatePrefsData
        get() = flow.value
        set(v) { flow.value = v }
    override val data: Flow<UpdatePrefsData> = flow
    override suspend fun edit(transform: (UpdatePrefsData) -> UpdatePrefsData) {
        flow.value = transform(flow.value)
    }
}

private val Context.updateDataStore by preferencesDataStore(name = "updates")

/** DataStore Preferences ("updates"). */
class DataStoreUpdatePrefs(context: Context) : UpdatePrefs {
    private val store = context.applicationContext.updateDataStore

    override val data: Flow<UpdatePrefsData> = store.data.map(::read).distinctUntilChanged()

    override suspend fun edit(transform: (UpdatePrefsData) -> UpdatePrefsData) {
        store.edit { prefs ->
            val next = transform(read(prefs))
            prefs[AUTO_CHECK] = next.autoCheck
            prefs.put(LAST_CHECK_AT, next.lastCheckAt)
            prefs.put(SKIPPED, next.skipped)
            prefs.put(DISMISSED, next.dismissed)
            prefs.put(LATEST, next.latest)
            prefs.put(LATEST_NOTES, next.latestNotes)
        }
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else set(key, value)
    }

    private fun read(p: Preferences) = UpdatePrefsData(
        autoCheck = p[AUTO_CHECK] ?: true,
        lastCheckAt = p[LAST_CHECK_AT],
        skipped = p[SKIPPED],
        dismissed = p[DISMISSED],
        latest = p[LATEST],
        latestNotes = p[LATEST_NOTES],
    )

    private companion object {
        val AUTO_CHECK = booleanPreferencesKey("auto_check")
        val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        val SKIPPED = stringPreferencesKey("skipped")
        val DISMISSED = stringPreferencesKey("dismissed")
        val LATEST = stringPreferencesKey("latest")
        val LATEST_NOTES = stringPreferencesKey("latest_notes")
    }
}
