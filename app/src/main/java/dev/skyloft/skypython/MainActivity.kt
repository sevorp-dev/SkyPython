package dev.skyloft.skypython

import android.content.Context
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.ui.theme.SkyPythonTheme

class MainActivity : ComponentActivity() {
    // Android 12 and older: the in-app language choice (13+ uses the system's per-app language).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so system bar icons must be light whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            SkyPythonTheme {
                val snackbar = remember { SnackbarHostState() }
                // The error banner floats above every screen (and above the bottom bar): SkyPythonApp draws it.
                SkyPythonApp(snackbar)
            }
        }
    }
}
