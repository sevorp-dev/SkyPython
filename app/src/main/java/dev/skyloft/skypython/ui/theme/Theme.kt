package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skyloft.skypython.R

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

// Material slots follow the palette. Every slot is set: any slot left out falls back to
// Material's baseline purple tones, which show up in dialogs, menus, text-field outlines and
// switch tracks.
private fun materialColors(c: SkyColors): ColorScheme = if (c.isLight) {
    lightColorScheme(
        primary = c.accentText,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDCC0),
        onPrimaryContainer = Color(0xFF3A1D00),
        inversePrimary = Color(0xFFF5A962),
        secondary = c.codeFunction,
        onSecondary = Color.White,
        secondaryContainer = c.raised,
        onSecondaryContainer = c.text,
        tertiary = Color(0xFF8A5A00),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFE2C2),
        onTertiaryContainer = Color(0xFF2C1A00),
        background = c.window,
        onBackground = c.text,
        surface = c.window,
        onSurface = c.text,
        surfaceVariant = c.raised,
        onSurfaceVariant = c.mutedText,
        // Flat: no orange tint added to raised surfaces.
        surfaceTint = Color.Transparent,
        // Snackbars: a dark slab with orange actions, the usual contrast on a light page.
        inverseSurface = DarkSkyColors.bar.copy(alpha = 0.97f),
        inverseOnSurface = DarkSkyColors.text,
        error = c.error,
        onError = Color.White,
        errorContainer = c.errorSurface,
        onErrorContainer = c.onErrorSurface,
        outline = c.outline,
        outlineVariant = c.line,
        scrim = Color(0xFF000000),
        surfaceBright = c.panel,
        surfaceDim = Color(0xFFE8E9EC),
        surfaceContainerLowest = c.panel,
        // Bottom sheets.
        surfaceContainerLow = c.panel.copy(alpha = 0.98f),
        // Menus float over content: slightly translucent.
        surfaceContainer = c.panel.copy(alpha = GLASS_ALPHA),
        // Dialogs stay opaque (they hold text and fields).
        surfaceContainerHigh = c.panel,
        surfaceContainerHighest = c.raised,
    )
} else {
    darkColorScheme(
        primary = c.accentText,
        onPrimary = c.onAccent,
        primaryContainer = Color(0xFF5A3313),
        onPrimaryContainer = Color(0xFFFFDCC0),
        inversePrimary = c.accent,
        secondary = c.codeFunction,
        onSecondary = Color(0xFF06233F),
        secondaryContainer = c.raised,
        onSecondaryContainer = c.text,
        tertiary = Color(0xFFFFC27A),
        onTertiary = Color(0xFF3A2400),
        tertiaryContainer = Color(0xFF4A3520),
        onTertiaryContainer = Color(0xFFFFE2C2),
        background = c.window,
        onBackground = c.text,
        surface = c.window,
        onSurface = c.text,
        surfaceVariant = c.panel,
        onSurfaceVariant = c.mutedText,
        surfaceTint = Color.Transparent,
        // Snackbars: a raised dark panel with orange actions, not a light slab.
        inverseSurface = c.raised.copy(alpha = 0.97f),
        inverseOnSurface = c.text,
        error = c.error,
        onError = Color(0xFF410002),
        errorContainer = Color(0xFF4D1F1D),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = c.outline,
        outlineVariant = c.line,
        scrim = Color(0xFF000000),
        surfaceBright = c.line,
        surfaceDim = c.console,
        surfaceContainerLowest = c.console,
        surfaceContainerLow = c.bar.copy(alpha = 0.97f),
        surfaceContainer = c.panel.copy(alpha = GLASS_ALPHA),
        surfaceContainerHigh = c.panel,
        surfaceContainerHighest = c.raised,
    )
}

private val DarkMaterialColors = materialColors(DarkSkyColors)
private val LightMaterialColors = materialColors(LightSkyColors)

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

/**
 * The app theme. [dark] picks [DarkSkyColors] or [LightSkyColors] (by default the system's
 * choice); screens read the palette through [SkyTheme.colors] and Material's slots follow it.
 */
@Composable
fun SkyPythonTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkSkyColors else LightSkyColors
    CompositionLocalProvider(LocalSkyColors provides colors) {
        MaterialTheme(
            colorScheme = if (dark) DarkMaterialColors else LightMaterialColors,
            typography = SkyTypography,
            shapes = SkyShapes,
            content = content,
        )
    }
}
