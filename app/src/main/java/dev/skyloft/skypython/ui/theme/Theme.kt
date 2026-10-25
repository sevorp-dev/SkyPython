package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand palette, from the SkyPython banner's night sky, toned down for a calm, flat tool:
// muted navy surfaces in clear elevation steps, one confident blue accent, violet only as a
// rare secondary touch. Text contrast is WCAG AA (≥ 4.5:1) on every surface below.

/** Deepest navy: console / terminal output, logs. */
val NightNavy = Color(0xFF090D1A)
/** Window and code area background (also used by the Sora view, see CodeEditor) and list screens. */
val EditorBackground = Color(0xFF0E1424)
/** Top bars, the tab strip, the symbol bar and key rows: one step above the background. */
val BarNavy = Color(0xFF151C2F)
/** Raised panels: cards, menus, dialogs, the drawer header. */
val PanelNavy = Color(0xFF1B2338)
/** 1dp hairlines between rows and sections. */
val DividerNavy = Color(0xFF273149)

/** The accent: solid fills behind white (Run, FABs, filled buttons, switch tracks) — 5.2:1 with white. */
val ElectricBlue = Color(0xFF2D63E8)
/** Accent as text / icons on navy (links, text buttons, selected marks) — ≥ 7:1 on every surface. */
val SkyBlue = Color(0xFF86AEFF)
/** Cursor and prompt. */
val SkyCyan = Color(0xFF6CC8EE)
/** Violet, the rare secondary accent. */
val Violet = Color(0xFF7C6CE0)
/** Violet as text / icons on navy. */
val SoftViolet = Color(0xFFB3A6F5)

/** Primary text. */
val TextBright = Color(0xFFE4E8F2)
/** Secondary text (subtitles, hints) — ≥ 6:1 on every surface. */
val MutedText = Color(0xFF9AA5BF)
val ErrorRed = Color(0xFFFF7A8A)
val SuccessGreen = Color(0xFF5FD38D)

/** The one gradient left: a soft blue → violet on the brand name in About. */
val BrandTextBrush: Brush = Brush.linearGradient(listOf(SkyBlue, SoftViolet))

// Every slot is set: any slot left out falls back to Material's baseline purple-grey tones,
// which show up in dialogs, menus, text-field outlines and switch tracks.
private val SkyColors = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color(0xFF0A1A42),
    primaryContainer = Color(0xFF213C80),
    onPrimaryContainer = Color(0xFFDCE6FF),
    inversePrimary = ElectricBlue,
    secondary = SkyCyan,
    onSecondary = Color(0xFF00283B),
    secondaryContainer = Color(0xFF243150),
    onSecondaryContainer = Color(0xFFD8E2F8),
    tertiary = SoftViolet,
    onTertiary = Color(0xFF231A5C),
    tertiaryContainer = Color(0xFF342D6B),
    onTertiaryContainer = Color(0xFFE6E0FF),
    background = EditorBackground,
    onBackground = TextBright,
    surface = EditorBackground,
    onSurface = TextBright,
    surfaceVariant = Color(0xFF222B42),
    onSurfaceVariant = MutedText,
    // Flat: no blue tint added to raised surfaces.
    surfaceTint = Color.Transparent,
    // Snackbars: a raised navy panel with light-blue actions, not a light slab.
    inverseSurface = Color(0xFF2A3450),
    inverseOnSurface = TextBright,
    error = ErrorRed,
    onError = Color(0xFF3B0010),
    errorContainer = Color(0xFF4A1C28),
    onErrorContainer = Color(0xFFFFD9DE),
    outline = Color(0xFF5E6985),
    outlineVariant = DividerNavy,
    scrim = Color(0xFF02040A),
    surfaceBright = Color(0xFF283250),
    surfaceDim = NightNavy,
    surfaceContainerLowest = NightNavy,
    surfaceContainerLow = Color(0xFF121929),
    surfaceContainer = BarNavy,
    surfaceContainerHigh = PanelNavy,
    surfaceContainerHighest = Color(0xFF222B42),
)

/** Consistent corners: 8dp small controls, 12dp menus / fields / cards, 16dp+ dialogs and sheets. */
private val SkyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun SkyPythonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SkyColors, shapes = SkyShapes, content = content)
}
