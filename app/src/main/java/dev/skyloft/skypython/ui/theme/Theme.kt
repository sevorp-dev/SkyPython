package dev.skyloft.skypython.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SkyBlue = Color(0xFF4FC3F7)
val ErrorRed = Color(0xFFFF6B6B)

private val SkyColors = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color(0xFF00283A),
    // Containers drive FABs, selected chips and dialogs; without them Material's purple shows.
    primaryContainer = Color(0xFF0B4A66),
    onPrimaryContainer = Color(0xFFCDEFFF),
    secondary = Color(0xFFFFD54F),
    onSecondary = Color(0xFF3A2E00),
    secondaryContainer = Color(0xFF243444),
    onSecondaryContainer = Color(0xFFD6E4F0),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF0F1720),
    surface = Color(0xFF0F1720),
    surfaceContainer = Color(0xFF16212C),
    error = ErrorRed,
)

@Composable
fun SkyPythonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SkyColors, content = content)
}
