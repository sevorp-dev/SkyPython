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

// "IDE dark": neutral near-black surfaces (no blue tint) in clear elevation steps, like the dark
// themes of desktop code editors, and one warm orange accent. Translucency is used sparingly — on
// floating layers (menus, sheets, snackbars, the drawer scrim) — never on text-heavy panels.
// Text contrast is WCAG AA (≥ 4.5:1) on every surface. (The Slate* names are historical.)

/** Darkest: console / terminal output and logs. */
val Ink = Color(0xFF161616)
/** The terminal's "Black" theme: near-black for OLED screens at night. */
val InkBlack = Color(0xFF0A0A0A)
/** Window and code-area background (also the Sora view's, see CodeEditor). */
val Slate = Color(0xFF1E1F22)
/** Top bars, the tab strip, the status line, symbol bar and key rows: one step above [Slate]. */
val SlateBar = Color(0xFF2B2D30)
/** Raised panels: cards, fields, menus, the completion popup. */
val SlatePanel = Color(0xFF313338)
/** Dialogs and pressed / selected rows: above [SlatePanel]. */
val SlateRaised = Color(0xFF37393E)
/** 1dp hairlines between rows and sections, and borders of floating layers. */
val SlateLine = Color(0xFF3C3E43)
/** Outlines of controls (text fields, the off switch): stronger than [SlateLine]. */
val SlateOutline = Color(0xFF5A5D63)

/** The accent: solid fills (Run, FABs, filled buttons, switch tracks) under [OnOrange] content. */
val Orange = Color(0xFFF28C38)
/** Content on [Orange]: a deep brown-black (≈ 7.4:1), sharper than white on orange (2.4:1). */
val OnOrange = Color(0xFF221203)
/** Accent as text / icons on dark surfaces (links, selected tabs, section labels) — ≥ 6:1 everywhere. */
val OrangeSoft = Color(0xFFF5A962)
/** A quiet orange wash behind selected rows and armed keys. */
val OrangeTint = Color(0x1FF28C38)

/** Primary text. */
val TextBright = Color(0xFFDFE1E5)
/** Secondary text (subtitles, hints) — 6.5:1 on [Slate], 5.5:1 on [SlateBar], 5.0:1 on [SlatePanel], 4.6:1 on [SlateRaised]. */
val MutedText = Color(0xFFA0A3AA)
/** Code functions / links in code — the cool counterpart of the orange keywords. */
val CodeBlue = Color(0xFF56A8F5)
val ErrorRed = Color(0xFFFF7B72)
val SuccessGreen = Color(0xFF7FD18B)
/** Warning squiggles. */
val WarningAmber = Color(0xFFE3B95C)
/** A problem banner's background and text (the editor's error strip). */
val ErrorSurface = Color(0xFF3A2B2D)
val OnErrorSurface = Color(0xFFFFD9DE)

// Code colours, shared by the editor theme (assets/textmate/skypython-dark.json) and previews.
/** Plain code text. */
val CodeText = Color(0xFFBCBEC4)
/** Keywords (def, class, import, True …): a muted warm orange. */
val CodeKeyword = Color(0xFFCF8E6D)
/** Control-flow keywords (if, return, for …): the accent orange. */
val CodeControl = Orange
val CodeString = Color(0xFF6AAB73)
/** The selected completion row and find matches: a warm, opaque take on the orange selection. */
val CodeSelection = Color(0xFF4A3B2B)
/** Completion-kind badges behind the orange letter. */
val BadgeTint = Color(0xFF3D3328)

/** Console text and its dim lines (prompts, notices). */
val ConsoleText = Color(0xFFD5D7DC)
val ConsoleDim = Color(0xFF85898F)

/** Brand name in About: a soft orange → amber sweep (no glow). */
val BrandTextBrush: Brush = Brush.linearGradient(listOf(Orange, Color(0xFFFFC27A)))

/** Floating layers (menus, sheets, snackbars) let the content shimmer through a little. */
const val GLASS_ALPHA = 0.96f

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
    // Snackbars: a raised dark panel with orange actions, not a light slab.
    inverseSurface = SlateRaised.copy(alpha = 0.97f),
    inverseOnSurface = TextBright,
    error = ErrorRed,
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF4D1F1D),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = SlateOutline,
    outlineVariant = SlateLine,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3C3E43),
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
