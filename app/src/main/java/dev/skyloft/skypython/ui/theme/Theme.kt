package dev.skyloft.skypython.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Brand palette, taken from the SkyPython banner: a night sky in navy with electric blue,
// sky cyan and violet glow.

/** Deepest night navy: console, logs, the window background. */
val NightNavy = Color(0xFF070B1F)
/** Code area background (also used by the Sora view, see CodeEditor) and list screens. */
val EditorBackground = Color(0xFF0B1230)
/** Navy of every top bar, the tab strip and the symbol bar. */
val BarNavy = Color(0xFF111A3A)
/** Raised panels: cards, menus, dialogs. */
val PanelNavy = Color(0xFF16214A)
/** Thin separators between list rows and sections. */
val DividerNavy = Color(0xFF222E5C)

/** Electric blue of the banner (Run button, accents behind white). */
val ElectricBlue = Color(0xFF2F6BFF)
/** Lighter electric blue, readable as text/icons on navy. */
val SkyBlue = Color(0xFF5B8CFF)
/** Sky cyan: prompts, cursor, highlights. */
val SkyCyan = Color(0xFF38BDF8)
/** Violet glow. */
val Violet = Color(0xFF8B5CF6)
/** Lighter violet, readable as text/icons on navy. */
val SoftViolet = Color(0xFFA78BFA)

/** Primary text on navy. */
val TextBright = Color(0xFFE6ECFF)
/** Secondary text (subtitles, hints) readable on [EditorBackground] and [BarNavy] (> 7:1). */
val MutedText = Color(0xFFA3B1D6)
val ErrorRed = Color(0xFFFF6B81)
val SuccessGreen = Color(0xFF4ADE80)

/** Brand text gradient: cyan → blue → violet (logo, app name). */
val BrandTextBrush: Brush = Brush.linearGradient(listOf(SkyCyan, SkyBlue, SoftViolet))
/** Run button fill: electric blue → violet. */
val RunBrush: Brush = Brush.linearGradient(listOf(Color(0xFF3B82F6), ElectricBlue, Violet))
/** Night-sky header: navy → blue → a hint of violet. */
val NightSkyBrush: Brush = Brush.linearGradient(
    listOf(Color(0xFF070B1F), Color(0xFF0F2070), Color(0xFF3B2590)),
)
/** Thin glowing accent line (tab marker, header edge). */
val GlowLineBrush: Brush = Brush.horizontalGradient(listOf(SkyCyan, SkyBlue, SoftViolet))

// Every slot is set: any slot left out falls back to Material's baseline purple-grey tones,
// which show up in dialogs, menus, text-field outlines and switch tracks.
private val SkyColors = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color(0xFF04102F),
    primaryContainer = Color(0xFF1C3A9E),
    onPrimaryContainer = Color(0xFFDCE6FF),
    inversePrimary = SkyCyan,
    secondary = SkyCyan,
    onSecondary = Color(0xFF00243A),
    secondaryContainer = Color(0xFF1B2A5E),
    onSecondaryContainer = Color(0xFFD6E2FF),
    tertiary = SoftViolet,
    onTertiary = Color(0xFF1E0B4A),
    tertiaryContainer = Color(0xFF3A2585),
    onTertiaryContainer = Color(0xFFEADDFF),
    background = EditorBackground,
    onBackground = TextBright,
    surface = EditorBackground,
    onSurface = TextBright,
    surfaceVariant = Color(0xFF1A2550),
    onSurfaceVariant = MutedText,
    surfaceTint = SkyBlue,
    // Snackbars: a raised navy panel with cyan actions, not a light slab.
    inverseSurface = Color(0xFF233066),
    inverseOnSurface = TextBright,
    error = ErrorRed,
    onError = Color(0xFF3B0010),
    errorContainer = Color(0xFF5C1A2A),
    onErrorContainer = Color(0xFFFFD9DF),
    outline = Color(0xFF5A6A9E),
    outlineVariant = DividerNavy,
    scrim = Color(0xFF02040F),
    surfaceBright = Color(0xFF243063),
    surfaceDim = NightNavy,
    surfaceContainerLowest = NightNavy,
    surfaceContainerLow = Color(0xFF0E1636),
    surfaceContainer = BarNavy,
    surfaceContainerHigh = PanelNavy,
    surfaceContainerHighest = Color(0xFF1C2855),
)

@Composable
fun SkyPythonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SkyColors, content = content)
}
