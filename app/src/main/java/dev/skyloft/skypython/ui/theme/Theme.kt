package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R

// "Modern dark, minimal": cool slate surfaces in clear elevation steps and one warm orange
// accent. Translucency is used sparingly — on floating layers (menus, sheets, snackbars, the
// drawer scrim) — never on text-heavy panels. Text contrast is WCAG AA (≥ 4.5:1) on every surface.

/** Deepest slate: console / terminal output and logs. */
val Ink = Color(0xFF161E28)
/** Window and code-area background (also the Sora view's, see CodeEditor). */
val Slate = Color(0xFF1C2530)
/** Top bars, the tab strip, the status line, symbol bar and key rows: one step above [Slate]. */
val SlateBar = Color(0xFF212B37)
/** Raised panels: cards, fields, menus, the completion popup. */
val SlatePanel = Color(0xFF273240)
/** Dialogs and pressed / selected rows: above [SlatePanel]. */
val SlateRaised = Color(0xFF2E3A49)
/** 1dp hairlines between rows and sections, and borders of floating layers. */
val SlateLine = Color(0xFF334051)

/** The accent: solid fills (Run, FABs, filled buttons, switch tracks) under [OnOrange] content. */
val Orange = Color(0xFFF28C38)
/** Content on [Orange]: a deep brown-black (≈ 7.9:1), sharper than white on orange (2.4:1). */
val OnOrange = Color(0xFF221203)
/** Accent as text / icons on slate (links, selected tabs, section labels) — ≥ 7:1 on [Slate]. */
val OrangeSoft = Color(0xFFF5A962)
/** A quiet orange wash behind selected rows and armed keys. */
val OrangeTint = Color(0x1FF28C38)

/** Primary text. */
val TextBright = Color(0xFFE8ECF1)
/** Secondary text (subtitles, hints) — 5.3:1 on [Slate], 4.6:1 on [SlatePanel]. */
val MutedText = Color(0xFF8C99A8)
/** Code functions / links in code — the cool counterpart of the orange keywords. */
val CodeBlue = Color(0xFF7AB8F5)
val ErrorRed = Color(0xFFFF7B72)
val SuccessGreen = Color(0xFF7FD18B)

/** Brand name in About: a soft orange → amber sweep (no glow). */
val BrandTextBrush: Brush = Brush.linearGradient(listOf(Orange, Color(0xFFFFC27A)))

/** Floating layers (menus, sheets, snackbars) let the content shimmer through a little. */
const val GLASS_ALPHA = 0.94f

/** UI text: Inter (SIL OFL 1.1), static Latin + Cyrillic subsets in res/font. */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    // Bold requests get SemiBold rather than a synthesized smear.
    Font(R.font.inter_semibold, FontWeight.Bold),
)

/** Code, console, terminal: JetBrains Mono (SIL OFL 1.1), ligatures off (see res/font). */
val CodeFont = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_regular, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

// Every slot is set: any slot left out falls back to Material's baseline purple tones,
// which show up in dialogs, menus, text-field outlines and switch tracks.
private val SkyColors = darkColorScheme(
    primary = OrangeSoft,
    onPrimary = OnOrange,
    primaryContainer = Color(0xFF5A3313),
    onPrimaryContainer = Color(0xFFFFDCC0),
    inversePrimary = Orange,
    secondary = CodeBlue,
    onSecondary = Color(0xFF06233F),
    secondaryContainer = SlateRaised,
    onSecondaryContainer = TextBright,
    tertiary = Color(0xFFFFC27A),
    onTertiary = Color(0xFF3A2400),
    tertiaryContainer = Color(0xFF4A3520),
    onTertiaryContainer = Color(0xFFFFE2C2),
    background = Slate,
    onBackground = TextBright,
    surface = Slate,
    onSurface = TextBright,
    surfaceVariant = SlatePanel,
    onSurfaceVariant = MutedText,
    // Flat: no orange tint added to raised surfaces.
    surfaceTint = Color.Transparent,
    // Snackbars: a raised slate panel with orange actions, not a light slab.
    inverseSurface = SlateRaised.copy(alpha = 0.97f),
    inverseOnSurface = TextBright,
    error = ErrorRed,
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF4D1F1D),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF5C6B7C),
    outlineVariant = SlateLine,
    scrim = Color(0xFF070A0E),
    surfaceBright = Color(0xFF354253),
    surfaceDim = Ink,
    surfaceContainerLowest = Ink,
    // Bottom sheets.
    surfaceContainerLow = SlateBar.copy(alpha = 0.97f),
    // Menus float over content: slightly translucent.
    surfaceContainer = SlatePanel.copy(alpha = GLASS_ALPHA),
    // Dialogs stay opaque (they hold text and fields).
    surfaceContainerHigh = SlatePanel,
    surfaceContainerHighest = SlateRaised,
)

private val SkyTypography: Typography = Typography().let { base ->
    fun TextStyle.inter() = copy(fontFamily = InterFamily)
    Typography(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = base.headlineSmall.inter().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.inter().copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.inter().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter().copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.inter().copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.inter().copy(letterSpacing = 0.sp),
        labelLarge = base.labelLarge.inter().copy(letterSpacing = 0.sp),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )
}

/** Consistent corners: 8dp small controls, 12dp menus / fields / cards, 16dp+ dialogs and sheets. */
private val SkyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun SkyPythonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SkyColors, typography = SkyTypography, shapes = SkyShapes, content = content)
}
