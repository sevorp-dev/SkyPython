package dev.skyloft.skypython

import android.content.Context
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.settings.SettingsStore
import dev.skyloft.skypython.ui.theme.SkyPythonTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    // Android 12 and older: the in-app language choice (13+ uses the system's per-app language).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val store = SettingsStore(applicationContext)
        val appTheme = store.settings.map { it.appTheme }.distinctUntilChanged()
        setContent {
            // Until the setting is read, nothing is drawn: the window background (which follows
            // the system theme) shows, so a chosen theme never flashes the other one first.
            val theme by appTheme.collectAsState(initial = null)
            val chosen = theme ?: return@setContent
            val dark = chosen.isDark(isSystemInDarkTheme())
            // Status and navigation bar icons: light on the dark theme, dark on the light one.
            LaunchedEffect(dark) { applySystemBars(dark) }
            SkyPythonTheme(dark = dark) {
                val snackbar = remember { SnackbarHostState() }
                // The error banner floats above every screen (and above the bottom bar): SkyPythonApp draws it.
                SkyPythonApp(snackbar)
            }
        }
    }

    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
