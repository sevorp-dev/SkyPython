package dev.skyloft.skypython.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The app's semantic colours. Screens read them through [SkyTheme.colors]; the two instances are
 * [DarkSkyColors] ("IDE dark": neutral near-black surfaces in clear elevation steps) and
 * [LightSkyColors] (calm off-white window, white bars and panels, hairline edges). One warm orange
 * accent in both. Text is WCAG AA (≥ 4.5:1) on every surface it sits on.
 */
@Immutable
data class SkyColors(
    val isLight: Boolean,
    /** Window and page background. */
    val window: Color,
    /** Top bars, the tab strip, the status line, symbol bar and key rows. */
    val bar: Color,
    /** Raised panels: cards, fields, menus, the completion popup. */
    val panel: Color,
    /** Dialogs' inner wells, pressed / selected rows, neutral chips: one step from [panel]. */
    val raised: Color,
    /** 1dp hairlines between rows and sections, and borders of floating layers. */
    val line: Color,
    /** Outlines of controls (text fields, the off switch): stronger than [line]. */
    val outline: Color,

    /** The accent as a solid fill (Run, FABs, filled buttons, switch tracks) under [onAccent]. */
    val accent: Color,
    /** Content on [accent]: a deep brown-black (≈ 7.4:1). */
    val onAccent: Color,
    /** The accent as text and icons on the window and panels (links, selected tabs, labels). */
    val accentText: Color,
    /** A quiet orange wash behind selected rows and armed keys. */
    val accentTint: Color,
    /** Tonal buttons (Install on each package): an orange wash under [accentText]. */
    val accentTonal: Color,

    /** Primary text. */
    val text: Color,
    /** Secondary text (subtitles, hints). */
    val mutedText: Color,

    val error: Color,
    val success: Color,
    /** Warning squiggles and notices. */
    val warning: Color,
    /** A problem banner's background and text (the editor's error strip, a failed update). */
    val errorSurface: Color,
    val onErrorSurface: Color,

    /** The code area (the Sora view) and code previews. */
    val codeBackground: Color,
    val codeText: Color,
    /** Keywords (def, class, import, True …). */
    val codeKeyword: Color,
    /** Control-flow keywords (if, return, for …). */
    val codeControl: Color,
    val codeString: Color,
    /** Function names. */
    val codeFunction: Color,
    /** The selected completion row and find matches: opaque, so highlighted text stays readable. */
    val codeSelection: Color,
    /** Completion-kind badges behind the orange letter. */
    val badge: Color,

    /** Console / terminal / log background (the interpreter, Run output, pip logs, image viewer). */
    val console: Color,
    val consoleText: Color,
    /** Dim console lines (prompts, notices). */
    val consoleDim: Color,

    /** Brand name in About: a soft orange sweep (no glow). */
    val brandBrush: Brush,
    /** Calm tints for letter avatars and sample icons, picked by name. */
    val avatarTints: List<Color>,
)

/** "IDE dark": the original SkyPython look. */
val DarkSkyColors = SkyColors(
    isLight = false,
    window = Color(0xFF1E1F22),
    bar = Color(0xFF2B2D30),
    panel = Color(0xFF313338),
    raised = Color(0xFF37393E),
    line = Color(0xFF3C3E43),
    outline = Color(0xFF5A5D63),
    accent = Color(0xFFF28C38),
    onAccent = Color(0xFF221203),
    accentText = Color(0xFFF5A962),
    accentTint = Color(0x1FF28C38),
    accentTonal = Color(0x2EF28C38),
    // 6.5:1 on window, 5.5:1 on bar, 5.0:1 on panel, 4.6:1 on raised.
    text = Color(0xFFDFE1E5),
    mutedText = Color(0xFFA0A3AA),
    error = Color(0xFFFF7B72),
    success = Color(0xFF7FD18B),
    warning = Color(0xFFE3B95C),
    errorSurface = Color(0xFF3A2B2D),
    onErrorSurface = Color(0xFFFFD9DE),
    codeBackground = Color(0xFF1E1F22),
    codeText = Color(0xFFBCBEC4),
    codeKeyword = Color(0xFFCF8E6D),
    codeControl = Color(0xFFF28C38),
    codeString = Color(0xFF6AAB73),
    codeFunction = Color(0xFF56A8F5),
    codeSelection = Color(0xFF4A3B2B),
    badge = Color(0xFF3D3328),
    console = Color(0xFF161616),
    consoleText = Color(0xFFD5D7DC),
    consoleDim = Color(0xFF85898F),
    brandBrush = Brush.linearGradient(listOf(Color(0xFFF28C38), Color(0xFFFFC27A))),
    avatarTints = listOf(
        Color(0xFFF5A962), // orange
        Color(0xFF7FB4F0), // blue
        Color(0xFF8FD19A), // green
        Color(0xFFC3A6F0), // violet
        Color(0xFFE8C46A), // amber
        Color(0xFF6FCFC4), // teal
    ),
)

/**
 * "IDE light", in the spirit of the light themes of desktop code editors: an off-white window,
 * white bars and panels set apart by hairlines, near-black text. The orange stays the fill
 * (with dark content, as in dark); as text and icons it is a deeper burnt orange (5:1 on white).
 */
val LightSkyColors = SkyColors(
    isLight = true,
    window = Color(0xFFF7F8FA),
    bar = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    raised = Color(0xFFF0F1F3),
    line = Color(0xFFE3E5E8),
    // 3.1:1 on white: a control's edge stays visible.
    outline = Color(0xFF8D939C),
    accent = Color(0xFFF28C38),
    onAccent = Color(0xFF221203),
    // 5.0:1 on white, 4.8:1 on the window.
    accentText = Color(0xFFB05400),
    accentTint = Color(0x24F28C38),
    accentTonal = Color(0x2EF28C38),
    // 15:1 and 6.0:1 on white; 5.6:1 muted on the window, 5.2:1 on raised.
    text = Color(0xFF1F2329),
    mutedText = Color(0xFF5E6470),
    error = Color(0xFFC42B1C),
    success = Color(0xFF1A7F37),
    warning = Color(0xFF9A6700),
    errorSurface = Color(0xFFFCEBEA),
    onErrorSurface = Color(0xFF8C1D18),
    codeBackground = Color(0xFFFFFFFF),
    codeText = Color(0xFF1F2329),
    codeKeyword = Color(0xFF0033B3),
    codeControl = Color(0xFF0033B3),
    codeString = Color(0xFF067D17),
    codeFunction = Color(0xFF00627A),
    codeSelection = Color(0xFFFCE3CC),
    badge = Color(0xFFFBE7D5),
    console = Color(0xFFFAFAFA),
    consoleText = Color(0xFF1F2329),
    // 4.8:1 on the console.
    consoleDim = Color(0xFF6B7079),
    brandBrush = Brush.linearGradient(listOf(Color(0xFFD9700F), Color(0xFFF09A3A))),
    avatarTints = listOf(
        Color(0xFFC25E0A), // orange
        Color(0xFF2F6FC4), // blue
        Color(0xFF2E8B47), // green
        Color(0xFF7E57C2), // violet
        Color(0xFF9A7400), // amber
        Color(0xFF13857A), // teal
    ),
)

/** The palette in effect; [SkyPythonTheme] provides it. */
val LocalSkyColors = staticCompositionLocalOf { DarkSkyColors }

object SkyTheme {
    /** The app's semantic colours for the current theme (light or dark). */
    val colors: SkyColors
        @Composable @ReadOnlyComposable get() = LocalSkyColors.current
}

/** Floating layers (menus, sheets, snackbars) let the content shimmer through a little. */
const val GLASS_ALPHA = 0.96f
