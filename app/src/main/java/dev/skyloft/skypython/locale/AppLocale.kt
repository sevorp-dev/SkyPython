package dev.skyloft.skypython.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.core.content.edit
import java.util.Locale

/** The app languages offered in Settings; [tag] is empty for "follow the system". */
enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Russian("ru"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            val language = tag.orEmpty().substringBefore(',').substringBefore('-').substringBefore('_')
            return entries.firstOrNull { it != System && it.tag.equals(language, ignoreCase = true) } ?: System
        }
    }
}

/**
 * Per-app language without AppCompat. Android 13+ keeps the choice itself ([LocaleManager],
 * also editable in the system's per-app language settings); older versions store it here and
 * [MainActivity][dev.skyloft.skypython.MainActivity] wraps its context with [wrap].
 */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    private val usesLocaleManager: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun current(context: Context): AppLanguage =
        if (usesLocaleManager) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            AppLanguage.fromTag(locales?.takeUnless { it.isEmpty }?.toLanguageTags())
        } else {
            AppLanguage.fromTag(stored(context))
        }

    /** Applies [language]; the activity is recreated in the new language. */
    fun set(activity: Activity, language: AppLanguage) {
        if (usesLocaleManager) {
            val list = if (language == AppLanguage.System) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(language.tag)
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = list // recreates it
        } else {
            prefs(activity).edit { putString(KEY_TAG, language.tag) }
            activity.recreate()
        }
    }

    /** For attachBaseContext: [base] in the chosen language on Android 12 and older. */
    fun wrap(base: Context): Context {
        if (usesLocaleManager) return base
        val language = AppLanguage.fromTag(stored(base))
        if (language == AppLanguage.System) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
        return base.createConfigurationContext(config)
    }

    /**
     * A context whose resources speak the app language, for text made outside composition
     * (view models, toasts from the application context). Resolve it each time: the language
     * can change while a view model lives.
     */
    fun localized(context: Context): Context = wrap(context)

    fun string(context: Context, @StringRes id: Int, vararg args: Any): String =
        localized(context).getString(id, *args)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stored(context: Context): String? =
        // applicationContext is null while an Application itself is still attaching.
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, null)
}
