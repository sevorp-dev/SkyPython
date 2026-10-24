package dev.skyloft.skypython.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SkyBlue = Color(0xFF4FC3F7)
val ErrorRed = Color(0xFFFF6B6B)

/** Dark blue-grey of every top bar, the tab strip and the symbol bar. */
val BarBlue = Color(0xFF1C2B36)
/** Code area background (also used by the Sora view, see CodeEditor) and list screens. */
val EditorBackground = Color(0xFF0F1720)
/** Run FAB. */
val RunAmber = Color(0xFFFFD54F)
/** Secondary text (subtitles, hints) readable on [EditorBackground] and [BarBlue]. */
val MutedText = Color(0xFFA9B7C3)
/** Thin separators between list rows and sections. */
val DividerBlue = Color(0xFF26343F)

// Every slot is set: any slot left out falls back to Material's baseline purple tones,
// which show up in dialogs, menus, text-field outlines and switch tracks.
private val SkyColors = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color(0xFF00283A),
    primaryContainer = Color(0xFF0B4A66),
    onPrimaryContainer = Color(0xFFCDEFFF),
    inversePrimary = Color(0xFF006590),
    secondary = RunAmber,
    onSecondary = Color(0xFF3A2E00),
    secondaryContainer = Color(0xFF243444),
    onSecondaryContainer = Color(0xFFD6E4F0),
    tertiary = Color(0xFF80CBC4),
    onTertiary = Color(0xFF003733),
    tertiaryContainer = Color(0xFF1E4A47),
    onTertiaryContainer = Color(0xFFB2EBE6),
    background = EditorBackground,
    onBackground = Color(0xFFE3EAF0),
    surface = EditorBackground,
    onSurface = Color(0xFFE3EAF0),
    surfaceVariant = Color(0xFF22303B),
    onSurfaceVariant = MutedText,
    surfaceTint = SkyBlue,
    inverseSurface = Color(0xFFE3EAF0),
    inverseOnSurface = Color(0xFF1C2B36),
    error = ErrorRed,
    onError = Color(0xFF3B0000),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF6E7F8D),
    outlineVariant = DividerBlue,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2E3D49),
    surfaceDim = EditorBackground,
    surfaceContainerLowest = Color(0xFF0A1118),
    surfaceContainerLow = Color(0xFF131D26),
    surfaceContainer = Color(0xFF16212C),
    surfaceContainerHigh = Color(0xFF1E2B36),
    surfaceContainerHighest = Color(0xFF263541),
)

@Composable
fun SkyPythonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SkyColors, content = content)
}
