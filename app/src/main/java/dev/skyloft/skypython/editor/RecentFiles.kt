package dev.skyloft.skypython.editor

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val MAX_RECENT_FILES = 10

/** [path] moved to the front, without duplicates, at most [max] entries. */
fun pushRecent(list: List<String>, path: String, max: Int = MAX_RECENT_FILES): List<String> =
    (listOf(path) + list.filter { it != path }).take(max)

fun forgetRecent(list: List<String>, path: String): List<String> = list.filter { it != path }

/** One absolute path per line (paths can't contain a newline on Android in practice; such ones are dropped). */
object RecentFilesCodec {
    fun encode(paths: List<String>): String = paths.filter { '\n' !in it }.joinToString("\n")
    fun decode(raw: String?): List<String> = raw?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}

private val Context.recentDataStore by preferencesDataStore(name = "recent_files")
private val RECENT_KEY = stringPreferencesKey("recent")

/** The last [MAX_RECENT_FILES] local files opened in the editor, newest first. */
class RecentFilesStore(context: Context) {
    private val store = context.applicationContext.recentDataStore

    val paths: Flow<List<String>> = store.data.map { RecentFilesCodec.decode(it[RECENT_KEY]) }

    suspend fun update(transform: (List<String>) -> List<String>) {
        store.edit { it[RECENT_KEY] = RecentFilesCodec.encode(transform(RecentFilesCodec.decode(it[RECENT_KEY]))) }
    }
}
