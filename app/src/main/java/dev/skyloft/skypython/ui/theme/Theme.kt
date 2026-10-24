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
    secondary = Color(0xFFFFD54F),
    background = Color(0xFF0F1720),
    surface = Color(0xFF0F1720),
    surfaceContainer = Color(0xFF16212C),
    error = ErrorRed,
)

@Composable
fun SkyPythonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SkyColors, content = content)
}
